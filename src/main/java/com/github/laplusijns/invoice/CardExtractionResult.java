package com.github.laplusijns.invoice;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@JsonClassDescription("以原圖及 OCR 證據解析出的名片資料、欄位來源與可信度。")
public class CardExtractionResult {

    @JsonPropertyDescription("最終名片欄位。")
    public BusinessCardRecognition card = new BusinessCardRecognition();

    @JsonPropertyDescription("每個非空欄位都必須有一筆來源與可信度評估。")
    public List<FieldAssessment> assessments = new ArrayList<>();

    @JsonPropertyDescription("圖片品質、OCR 衝突或其他可能影響辨識的警告；沒有警告時回傳空陣列。")
    public List<String> warnings = new ArrayList<>();

    BusinessCardRecognition safeCard() {
        return card == null ? new BusinessCardRecognition() : card;
    }

    List<FieldAssessment> safeAssessments() {
        return assessments == null ? List.of() : assessments;
    }

    Optional<FieldAssessment> assessment(final CardField field) {
        return safeAssessments().stream()
                .filter(assessment -> assessment != null && field.apiName().equals(assessment.field))
                .findFirst();
    }
}
