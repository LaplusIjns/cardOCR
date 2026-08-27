package com.github.laplusijns.recognition;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("名片欄位判斷結果；無法確認的欄位必須是空字串。")
public class BusinessCardRecognition {
    @JsonPropertyDescription("公司完整名稱")
    public String companyName = "";

    @JsonPropertyDescription("名片持有人姓名")
    public String name = "";

    @JsonPropertyDescription("完整職稱與部門")
    public String jobTitle = "";

    @JsonPropertyDescription("一般電話或公司電話")
    public String telephone = "";

    @JsonPropertyDescription("行動電話或手機")
    public String mobilePhone = "";

    @JsonPropertyDescription("傳真號碼")
    public String fax = "";

    @JsonPropertyDescription("電子郵件地址")
    public String email = "";

    @JsonPropertyDescription("公司、辦公室或聯絡地址")
    public String address = "";

    @JsonPropertyDescription("有明確標籤的八位數統一編號")
    public String businessNumber = "";

    @JsonPropertyDescription("有明確標籤的股票代號")
    public String stockCode = "";

    @JsonPropertyDescription("公司官方網站網址")
    public String companyWebsite = "";

    public BusinessCardRecognition copy() {
        final BusinessCardRecognition copy = new BusinessCardRecognition();
        copy.companyName = companyName;
        copy.name = name;
        copy.jobTitle = jobTitle;
        copy.telephone = telephone;
        copy.mobilePhone = mobilePhone;
        copy.fax = fax;
        copy.email = email;
        copy.address = address;
        copy.businessNumber = businessNumber;
        copy.stockCode = stockCode;
        copy.companyWebsite = companyWebsite;
        return copy;
    }
}
