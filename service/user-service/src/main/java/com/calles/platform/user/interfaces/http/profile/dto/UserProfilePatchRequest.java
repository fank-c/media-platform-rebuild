package com.calles.platform.user.interfaces.http.profile.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 资料 PATCH 请求，通过 setter 的存在标记区分“未提交字段”和“显式 null”。
 */
public class UserProfilePatchRequest {

    /** 客户端读取到的并发版本，缺失资料首次保存必须为 0。 */
    private Long revision;
    /** 昵称值，可显式清空。 */
    private String nickname;
    /** 昵称是否出现在 JSON 中。 */
    private boolean nicknamePresent;
    /** 简介值，可显式清空。 */
    private String bio;
    /** 简介是否出现在 JSON 中。 */
    private boolean bioPresent;
    /** 城市值，可显式清空。 */
    private String city;
    /** 城市是否出现在 JSON 中。 */
    private boolean cityPresent;
    /** 生日值，可显式清空。 */
    private LocalDate birthday;
    /** 生日是否出现在 JSON 中。 */
    private boolean birthdayPresent;

    /** 返回并发版本。 */
    public Long getRevision() { return revision; }
    /** 设置并发版本。 */
    public void setRevision(Long revision) { this.revision = revision; }
    /** 返回昵称。 */
    public String getNickname() { return nickname; }
    /** 设置昵称并标记字段已提交。 */
    public void setNickname(String nickname) { this.nickname = nickname; this.nicknamePresent = true; }
    /** 返回简介。 */
    public String getBio() { return bio; }
    /** 设置简介并标记字段已提交。 */
    public void setBio(String bio) { this.bio = bio; this.bioPresent = true; }
    /** 返回城市。 */
    public String getCity() { return city; }
    /** 设置城市并标记字段已提交。 */
    public void setCity(String city) { this.city = city; this.cityPresent = true; }
    /** 返回生日。 */
    public LocalDate getBirthday() { return birthday; }
    /** 设置生日并标记字段已提交。 */
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; this.birthdayPresent = true; }
    /** 判断昵称是否提交。 */
    public boolean isNicknamePresent() { return nicknamePresent; }
    /** 判断简介是否提交。 */
    public boolean isBioPresent() { return bioPresent; }
    /** 判断城市是否提交。 */
    public boolean isCityPresent() { return cityPresent; }
    /** 判断生日是否提交。 */
    public boolean isBirthdayPresent() { return birthdayPresent; }
    /** 判断请求是否包含至少一个允许修改的字段。 */
    public boolean hasEditableField() { return nicknamePresent || bioPresent || cityPresent || birthdayPresent; }

    /**
     * 返回本次请求明确提交的字段名，仅用于管理审计，不包含任何资料值。
     *
     * @return 按固定字段顺序排列的不可修改列表
     */
    public List<String> submittedFields() {
        List<String> fields = new ArrayList<>(4);
        if (nicknamePresent) { fields.add("nickname"); }
        if (bioPresent) { fields.add("bio"); }
        if (cityPresent) { fields.add("city"); }
        if (birthdayPresent) { fields.add("birthday"); }
        return List.copyOf(fields);
    }
}
