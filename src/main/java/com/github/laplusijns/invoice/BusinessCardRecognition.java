package com.github.laplusijns.invoice;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("名片資訊解析結果。所有內容只能來自圖片理解階段提供的文字證據，無法確認時回傳空字串。")
public class BusinessCardRecognition {

    @JsonPropertyDescription("公司完整名稱。保留文字證據中的原始文字；無法確認時回傳空字串。")
    public String companyName = "";

    @JsonPropertyDescription("""
			名片持有人的姓名。
			中文姓名優先；同時有中文名及英文名時，格式為「中文姓名（英文姓名）」。
			無法確認時回傳空字串。
			""")
    public String name = "";

    @JsonPropertyDescription("""
			名片持有人的完整職稱與所屬部門、單位。
			不可將公司名稱、產品名稱或公司介紹誤認為職稱。
			無法確認時回傳空字串。
			""")
    public String jobTitle = "";

    @JsonPropertyDescription("""
			一般電話或公司電話。只有明確標示為 T、Tel、Telephone、電話等才填入。
			保留國碼、括號、空格、連字號及分機等原始格式；多個值以「、」連接。
			無法確認時回傳空字串。
			""")
    public String telephone = "";

    @JsonPropertyDescription("""
			行動電話或手機號碼。只有明確標示為 M、Mobile、Cell、手機、行動電話等才填入。
			保留原始格式；多個值以「、」連接。無法確認時回傳空字串。
			""")
    public String mobilePhone = "";

    @JsonPropertyDescription("""
			傳真號碼。只有明確標示 F、Fax、FAX、傳真時才可填入，不可用未分類電話猜測。
			保留原始格式；多個值以「、」連接。無法確認時回傳空字串。
			""")
    public String fax = "";

    @JsonPropertyDescription("""
			電子郵件地址。必須逐字採用文字證據，不可自行修正常見拼法。
			多個值以「、」連接；無法確認時回傳空字串。
			""")
    public String email = "";

    @JsonPropertyDescription("""
			名片上的公司、辦公室或聯絡地址。保留郵遞區號與原始地址文字。
			多個地址以「、」連接；無法確認時回傳空字串。
			""")
    public String address = "";

    @JsonPropertyDescription("""
			明確標示的統一編號、統編、Unified Business Number 或 Tax ID。
			只回傳號碼，不含標籤；不可僅因為是 8 位數字就推測為統編。
			多個值以「、」連接；無法確認時回傳空字串。
			""")
    public String businessNumber = "";

    @JsonPropertyDescription("""
			名片上明確標示的股票代號。只回傳代號，不含標籤，不可根據公司名稱猜測。
			多個值以「、」連接；無法確認時回傳空字串。
			""")
    public String stockCode = "";

    @JsonPropertyDescription("""
			名片上明確可見的公司官方網站網址。只回傳網址，不含標籤、社群帳號或 Email。
			多個值以「、」連接；無法確認時回傳空字串。
			""")
    public String companyWebsite = "";
}
