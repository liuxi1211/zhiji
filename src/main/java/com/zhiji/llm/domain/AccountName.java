package com.zhiji.llm.domain;

/** 账号名（用量列表展示用）：sys_customer 行投影。 */
public class AccountName {

    private long id;
    private String username;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
