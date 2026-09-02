package com.yyba.cloud.launcher;

import com.yyba.cloud.entity.User;
import com.yyba.cloud.util.TimeUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 启动器用户列表表格行。
 */
public class UserRow {

    private final Long id;
    private final String username;
    private final String displayName;
    private final String role;
    private final int enabled;

    private final StringProperty usernameProperty = new SimpleStringProperty();
    private final StringProperty displayNameProperty = new SimpleStringProperty();
    private final StringProperty roleLabelProperty = new SimpleStringProperty();
    private final StringProperty statusProperty = new SimpleStringProperty();
    private final StringProperty createdAtProperty = new SimpleStringProperty();

    public UserRow(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();
        this.role = user.getRole();
        this.enabled = user.getEnabled() != null ? user.getEnabled() : 1;

        usernameProperty.set(username);
        displayNameProperty.set(displayName);
        roleLabelProperty.set("ADMIN".equalsIgnoreCase(role) ? "管理员" : "成员");
        statusProperty.set(enabled == 1 ? "正常" : "已禁用");
        createdAtProperty.set(TimeUtil.toDisplay(user.getCreatedAt()));
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public int getEnabled() {
        return enabled;
    }

    public StringProperty usernameProperty() {
        return usernameProperty;
    }

    public StringProperty displayNameProperty() {
        return displayNameProperty;
    }

    public StringProperty roleLabelProperty() {
        return roleLabelProperty;
    }

    public StringProperty statusProperty() {
        return statusProperty;
    }

    public StringProperty createdAtProperty() {
        return createdAtProperty;
    }
}
