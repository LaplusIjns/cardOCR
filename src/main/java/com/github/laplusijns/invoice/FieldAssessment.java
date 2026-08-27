package com.github.laplusijns.invoice;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

@JsonClassDescription("單一名片欄位的來源與可信度評估。")
public class FieldAssessment {

    @JsonPropertyDescription(
            "欄位名稱，只能是 companyName、name、jobTitle、telephone、mobilePhone、fax、email、address、businessNumber、stockCode、companyWebsite。")
    public String field = "";

    @JsonPropertyDescription(
            "支持此欄位值的 OCR block ID。若值是直接由原圖確認而 OCR evidence 沒有對應 block，使用 IMAGE。")
    public List<String> sourceBlockIds = new ArrayList<>();

    @JsonPropertyDescription("0 到 1 的可信度。完全清晰且圖片與 OCR 一致才可接近 1。")
    public double confidence;

    @JsonPropertyDescription("仍可能成立的其他字元或欄位值；沒有替代值時回傳空陣列。")
    public List<String> alternatives = new ArrayList<>();

    List<String> safeSourceBlockIds() {
        return sourceBlockIds == null ? List.of() : sourceBlockIds;
    }

    List<String> safeAlternatives() {
        return alternatives == null ? List.of() : alternatives;
    }
}
