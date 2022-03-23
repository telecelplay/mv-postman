package org.meveo.model.customEntities;

import org.meveo.model.CustomEntity;
import java.util.List;
import org.meveo.model.persistence.DBStorageType;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class PostmanConfigurations implements CustomEntity {

    public PostmanConfigurations() {
    }

    public PostmanConfigurations(String uuid) {
        this.uuid = uuid;
    }

    private String uuid;

    @JsonIgnore()
    private DBStorageType storages;

    private Map<String, String> context = new HashMap<>();

    private Long failedTests;

    private String collection;

    @Override()
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public DBStorageType getStorages() {
        return storages;
    }

    public void setStorages(DBStorageType storages) {
        this.storages = storages;
    }

    public Map<String, String> getContext() {
        return context;
    }

    public void setContext(Map<String, String> context) {
        this.context = context;
    }

    public Long getFailedTests() {
        return failedTests;
    }

    public void setFailedTests(Long failedTests) {
        this.failedTests = failedTests;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    @Override()
    public String getCetCode() {
        return "PostmanConfigurations";
    }
}
