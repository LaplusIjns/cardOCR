# cardOCR

名片與文件辨識系統，採用「Java 主應用程式 + PaddleX/PaddleOCR 服務 + OpenAI API」分層架構。

## 處理流程

```text
圖片 / PDF
  -> Spring Boot / Hilla 主應用程式
  -> PaddleX POST http://127.0.0.1:16601/ocr
  -> OCR Block（文字、Bounding Box、Confidence、頁碼）
  -> 簡體中文／日文新字體漢字偵測與臺灣繁體正規化
  -> Java Layout Reconstruction
  -> Java Semantic Normalization + Rule Engine
  -> 無歧義：直接驗證並產生 DTO
  -> 有歧義：OCR 上下文 + 必要的局部裁圖 -> OpenAI Responses API
  -> Structured Output -> Java DTO -> 正規化 -> SQLite / 後續系統
```

PaddleOCR 回傳的每個 block 會先以 OpenCC4J 將簡體中文與日文新字體漢字正規化為臺灣繁體，再進行版面重建，例如 `业务` 轉為 `業務`、`株式会社` 轉為 `株式會社`、`東京駅` 轉為 `東京驛`。這是依 OpenCC 字詞表執行的確定性正規化，不會交給模型自由翻譯，平假名與片假名保持原樣。轉換只修改 `OcrBlock.text`；Bounding Box、Confidence、頁碼與頁面影像維持不變，Email 與 URL 也會被保護而不改寫。

OCR block 不是業務欄位。例如 PaddleOCR 分別回傳同一行的 `F` 與 `03-12345678` 時，Java 會先依座標重建為 `F | 03-12345678`，再由規則引擎判定為 Fax。若姓名因排版字距被辨識成 `王 | 小 | 明` 或 `王 | O | O`，版面層會保留原始 block，同時建立帶來源座標的緊密候選；規則引擎只將其路由為疑似姓名，最後由文字上下文與優先局部裁圖確認。若 OCR 只偵測到單一常見姓氏，也會擴大裁圖以涵蓋旁邊未形成文字 block 的字形。只有低信心值、未知標籤、未分類電話或其他無法由規則確定的文字才會呼叫 OpenAI。

詳細的元件職責與資料合約見 [架構說明](docs/architecture.md)。

## 啟動 PaddleX OCR Service

安裝 PaddleX OCR 與 serving plugin 後啟動官方 OCR pipeline：

```bash
python -m pip install "paddlex[ocr]"
paddlex --install serving
paddlex --serve --pipeline OCR --host 127.0.0.1 --port 16601
```

Java 預設呼叫 `http://127.0.0.1:16601/ocr`。服務需回傳 PaddleX 官方 serving 格式：`result.ocrResults[*].prunedResult`，其 `rec_texts`、`rec_scores`、`rec_boxes` 或 `rec_polys` 會映射成 Java OCR domain model。PDF 會以 `fileType: 0` 傳送，圖片則為 `fileType: 1`。

## 設定

可透過 Spring properties 或等價環境設定調整：

| Property | 預設值 | 用途 |
| --- | --- | --- |
| `card-ocr.paddlex.base-url` | `http://127.0.0.1:16601` | PaddleX 服務位址 |
| `card-ocr.paddlex.endpoint` | `/ocr` | OCR endpoint |
| `card-ocr.paddlex.return-page-images` | `true` | 取得頁面影像供局部裁圖；可關閉以減少本機傳輸 |
| `card-ocr.rules.deterministic-confidence` | `0.85` | Java 規則可直接採信的最低 OCR confidence |
| `card-ocr.openai.base-url` | `spring.ai.openai.base-url` 或官方 API | OpenAI API base URL |
| `card-ocr.openai.api-key` | `spring.ai.openai.api-key` | OpenAI API key |
| `card-ocr.openai.model` | `spring.ai.openai.chat.options.model` 或 `gpt-4o-mini` | 支援 image input 與 Structured Outputs 的模型 |
| `card-ocr.image-storage-path` | `./uploads` | 原始圖片/PDF 儲存位置 |

OpenAI key 只有在文件存在語意歧義、確實需要模型時才是必要的。OpenAI 請求使用 Responses API 的 JSON Schema Structured Output；Java 會再次驗證欄位格式，且模型不能覆寫 Java 規則已確定的值。

## 執行

需要 Java 25：

```powershell
.\mvnw.cmd spring-boot:run
```

執行測試：

```powershell
.\mvnw.cmd test
```

前端支援 JPEG、PNG、WebP、GIF 與 PDF，每個檔案上限 10 MB，一次最多 20 個檔案。
