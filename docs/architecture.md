# cardOCR 分層架構

## 設計原則

- PaddleX/PaddleOCR 負責「看出文字與位置」：文件前處理、文字偵測/辨識、Bounding Box、Confidence、頁碼。
- Java 負責「正規化、重建、規則與控制」：簡體與日文新字體漢字轉臺灣繁體、版面重建、標籤正規化、規則判斷、歧義路由、局部裁圖、格式驗證與持久化。
- OpenAI 負責「無法由 OCR 與規則確定的語意」：一般歧義只接收必要上下文；核心欄位在第一次流程後仍空白時，才接收完整頁面做一次最終確認。

## 元件與責任

| 層 | 主要類別 | 責任 |
| --- | --- | --- |
| Application | `ProcessService` | 接收圖片/PDF、排程、保存原始文件、寫入業務資料 |
| OCR port | `OcrClient` | 定義 Java 與 OCR 服務之間的界面 |
| OCR adapter | `PaddleXOcrClient` | 呼叫 PaddleX `/ocr` 並保留文字、座標、信心值、頁碼 |
| OCR domain | `OcrDocument` / `OcrPage` / `OcrBlock` / `BoundingBox` | 不含業務欄位的原始 OCR 結果 |
| OCR text normalization | `TraditionalChineseOcrNormalizer` | 將簡體與日文新字體漢字轉成臺灣繁體；保護 Email/URL，保留所有 OCR 中繼資料 |
| Layout | `LayoutReconstructionService` / `LayoutTextCandidate` | 依 Y 軸重疊、中心距離與 X 排序重建同行資料，並為字距分散的 CJK block 建立非破壞性緊密候選 |
| Semantic | `SemanticNormalizer` | 將 T/TEL/Phone、F/FAX、M/Mobile、E/Email 等標籤正規化 |
| Rules | `BusinessCardRuleEngine` | 高信心且格式正確時直接分類；產生明確的 ambiguity reason |
| Crop | `ImageCropService` | 依歧義行 Bounding Box 裁圖，並在最終確認時提供最多四張完整頁面 |
| AI ports | `SemanticDisambiguator` / `MissingFieldVisionVerifier` | 分離一般語意消歧與核心缺欄位的最終視覺確認 |
| AI adapter | `OpenAiSemanticDisambiguator` | 以 Responses API 傳送座標上下文、局部裁圖或完整頁面，要求 JSON Schema Structured Output |
| Validation | `BusinessCardValidator` | 去除空白/重複值、驗證 Email/電話/統編/網址並限制 DTO 長度 |
| Orchestration | `BusinessCardRecognitionPipeline` | 串接以上步驟，保證確定性規則優先且不可被 AI 覆寫 |

## OCR 合約

PaddleX request：

```json
{
  "file": "<base64>",
  "fileType": 1,
  "useDocOrientationClassify": true,
  "useDocUnwarping": true,
  "useTextlineOrientation": true,
  "textRecScoreThresh": 0.0,
  "visualize": true
}
```

`fileType` 的 `0` 代表 PDF、`1` 代表圖片。`textRecScoreThresh` 保持 0，讓 Java 看得到低信心 block 並自行路由，而不是在 OCR 層提前遺失資訊。

PaddleX response 中每個 `ocrResults` 元素是一頁。Adapter 會平行對齊：

```text
rec_texts[i]  -> OcrBlock.text
rec_scores[i] -> OcrBlock.confidence
rec_boxes[i]  -> OcrBlock.boundingBox
頁面陣列索引  -> OcrBlock.pageNumber（從 1 開始）
```

若服務只回傳 `rec_polys`，Java 會以 polygon 的最小/最大 X、Y 轉成 Bounding Box。OCR 結果在 Layout Reconstruction 完成前不會映射至 `telephone`、`fax` 等業務欄位。

## 簡體與日文漢字轉繁體

`TraditionalChineseOcrNormalizer` 位於 PaddleX adapter 與 Layout Reconstruction 之間。每個 OCR block 先使用 OpenCC4J 日文字體字典將 Shinjitai 漢字轉為標準繁體，再將其餘簡體字與詞組轉為臺灣繁體，例如 `株式会社` 轉為 `株式會社`、`東京駅` 轉為 `東京驛`、`业务经理` 轉為 `業務經理`、`使用互联网` 轉為 `使用網際網路`。

這個步驟依 OpenCC 的日文字體字詞表做確定性正規化，不交給模型自由翻譯；平假名與片假名保持原樣。正規化只建立文字已變更的新 `OcrBlock`，並沿用原本的 block id、頁碼、Bounding Box 與 Confidence。文字不需變更時會直接沿用原 `OcrDocument`。Email 與 URL 被視為不可改寫的識別碼，即使網址路徑內含簡體字或日文漢字也保持原值。

## 歧義路由

規則引擎在下列情況產生 ambiguity：

- OCR confidence 低於門檻；
- 標籤疑似誤讀，例如 `P | 03-12345678`；
- 已辨識標籤右側的值不符合預期格式；
- 電話沒有可確定類型的標籤；
- 同行的分散 CJK block 形成疑似姓名候選；
- 公司、姓名、職稱等需要上下文的未分類文字。

版面重建不會把 `王 | 小 | 明` 或 `王 | O | O` 直接覆寫成姓名，而是保留原始行，另附 `compactTextCandidates`、來源 block id、候選 Bounding Box 與 confidence。候選只連接同行、字高相近、中心接近且水平間距在相對字級門檻內的姓名型 block，必須包含 CJK 字元，但允許 OCR 易混淆的 `O`、`o`、`0`、圓圈、方框及全形拉丁字母，避免在送交圖片驗證前遺失可能姓名。純拉丁字母列不會因此成為姓名候選。

低信心、未知標籤、格式衝突或疑似姓名的區域會嘗試局部裁圖；疑似姓名優先使用其候選 Bounding Box，避免被最多四張的裁圖上限排除。若 OCR 只偵測到單一常見姓氏，裁圖會向周圍擴張以納入可能未被 OCR 偵測的其餘姓名字形。圖片可直接從原始檔裁切；PDF 優先使用 PaddleX 回傳的每頁 `inputImage`。無法取得可解碼頁面影像時仍會傳送原始文字、緊密候選、座標、相鄰 block 與前後行，不會退回整份文件上傳。

## OpenAI Structured Output

AI adapter 使用 `POST /v1/responses`，在 `text.format` 指定 `type: json_schema`、`strict: true`，且所有 DTO 欄位均為 required string。回傳後仍須經 Java 驗證器處理；模型回傳只合併到尚未由規則確定的欄位。

## 核心缺欄位最終確認

第一次規則與語意流程完成並正規化後，Pipeline 只檢查 `name`、`companyName`、`jobTitle`。任一核心欄位仍空白時，`ImageCropService` 優先採用 PaddleX 回傳的頁面影像；一般圖片缺少頁面影像時改用原始檔。PDF 必須有 PaddleX 頁面影像才能進行此步驟。

`MissingFieldVisionVerifier` 每份文件最多呼叫一次，輸入完整頁面、OCR 版面、目前已確認欄位與 `missingFields`。無論模型回傳什麼內容，Java 只合併呼叫前仍空白且列在 `missingFields` 的三個核心欄位；電話、Email、傳真等欄位及第一次流程已確認的核心欄位都不可被覆寫。最終輸出會再次通過 `BusinessCardValidator`。

## 失敗邊界

- PaddleX HTTP/格式錯誤：整份辨識失敗，原始文件保留，可重新辨識。
- 文件無歧義且三個核心欄位均有值：不呼叫 OpenAI。
- 文件有歧義或核心欄位需要最終確認但缺少 OpenAI key：明確回報設定錯誤，不把不確定資料偽裝成確定結果。
- 局部裁圖失敗：保留文字與版面上下文繼續語意判斷。
- OpenAI 格式或 refusal 錯誤：拒絕寫入未驗證輸出，交由既有失敗/重試流程處理。
