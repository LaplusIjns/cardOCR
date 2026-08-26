package com.github.laplusijns.card;

import com.github.laplusijns.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "business_cards")
public class BusinessCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "person_name", length = 100)
    private String name;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @Column(length = 100)
    private String telephone;

    @Column(name = "mobile_phone", length = 100)
    private String mobilePhone;

    @Column(length = 100)
    private String fax;

    @Column(length = 320)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(length = 2000)
    private String notes;

    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    @Column(name = "image_id", length = 36)
    private String imageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BusinessCard() {}

    public BusinessCard(final UserAccount user, final String imageId, final String imagePath) {
        this.user = user;
        this.imageId = imageId;
        this.imagePath = imagePath;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(final String companyName) {
        this.companyName = companyName;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(final String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getTelephone() {
        return telephone;
    }

    public void setTelephone(final String telephone) {
        this.telephone = telephone;
    }

    public String getMobilePhone() {
        return mobilePhone;
    }

    public void setMobilePhone(final String mobilePhone) {
        this.mobilePhone = mobilePhone;
    }

    public String getFax() {
        return fax;
    }

    public void setFax(final String fax) {
        this.fax = fax;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(final String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(final String address) {
        this.address = address;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(final String notes) {
        this.notes = notes;
    }

    public String getImagePath() {
        return imagePath;
    }

    public String getImageId() {
        return imageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
