# cardOCR 分層架構

## 設計原則

- PaddleX/PaddleOCR 負責「看出文字與位置」：文件前處理、文字偵測/辨識、Bounding Box、Confidence、頁碼。
- Java 負責「重建、規則與控制」：版面重建、標籤正規化、規則判斷、歧義路由、局部裁圖、格式驗證與持久化。
- OpenAI 負責「無法由 OCR 與規則確定的語意」：只接收必要上下文，不重新處理每張完整文件。

## 元件與責任

| 層 | 主要類別 | 責任 |
| --- | --- | --- |
| Application | `ProcessService` | 接收圖片/PDF、排程、保存原始文件、寫入業務資料 |
| OCR port | `OcrClient` | 定義 Java 與 OCR 服務之間的界面 |
| OCR adapter | `PaddleXOcrClient` | 呼叫 PaddleX `/ocr` 並保留文字、座標、信心值、頁碼 |
| OCR domain | `OcrDocument` / `OcrPage` / `OcrBlock` / `BoundingBox` | 不含業務欄位的原始 OCR 結果 |
| Layout | `LayoutReconstructionService` | 依 Y 軸重疊、中心距離與 X 排序將 block 重建成同行資料 |
| Semantic | `SemanticNormalizer` | 將 T/TEL/Phone、F/FAX、M/Mobile、E/Email 等標籤正規化 |
| Rules | `BusinessCardRuleEngine` | 高信心且格式正確時直接分類；產生明確的 ambiguity reason |
| Crop | `ImageCropService` | 依歧義行 Bounding Box 加 padding 裁圖，最多四張 |
| AI adapter | `OpenAiSemanticDisambiguator` | 以 Responses API 傳送座標上下文與局部裁圖，要求 JSON Schema Structured Output |
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

## 歧義路由

規則引擎在下列情況產生 ambiguity：

- OCR confidence 低於門檻；
- 標籤疑似誤讀，例如 `P | 03-12345678`；
- 已辨識標籤右側的值不符合預期格式；
- 電話沒有可確定類型的標籤；
- 公司、姓名、職稱等需要上下文的未分類文字。

低信心、未知標籤或格式衝突的區域會嘗試局部裁圖。圖片可直接從原始檔裁切；PDF 優先使用 PaddleX 回傳的每頁 `inputImage`。無法取得可解碼頁面影像時仍會傳送文字、座標、相鄰 block 與前後行，不會退回整份文件上傳。

## OpenAI Structured Output

AI adapter 使用 `POST /v1/responses`，在 `text.format` 指定 `type: json_schema`、`strict: true`，且所有 DTO 欄位均為 required string。回傳後仍須經 Java 驗證器處理；模型回傳只合併到尚未由規則確定的欄位。

## 失敗邊界

- PaddleX HTTP/格式錯誤：整份辨識失敗，原始文件保留，可重新辨識。
- 文件無歧義：不需要 OpenAI API key，也不呼叫 OpenAI。
- 文件有歧義但缺少 OpenAI key：明確回報設定錯誤，不把不確定資料偽裝成確定結果。
- 局部裁圖失敗：保留文字與版面上下文繼續語意判斷。
- OpenAI 格式或 refusal 錯誤：拒絕寫入未驗證輸出，交由既有失敗/重試流程處理。
