package org.meveo.model.customEntities;

import org.meveo.model.CustomEntity;
import java.util.List;
import org.meveo.model.persistence.DBStorageType;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class PostmanTestConfig implements CustomEntity {

    public PostmanTestConfig() {
    }

    public PostmanTestConfig(String uuid) {
        this.uuid = uuid;
    }

    private String uuid;

    @JsonIgnore()
    private DBStorageType storages;

    private Long failedTest;

    private Long totalTest;

    private String collectionFile;

    private String environmentFile;

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

    public Long getFailedTest() {
        return failedTest;
    }

    public void setFailedTest(Long failedTest) {
        this.failedTest = failedTest;
    }

    public Long getTotalTest() {
        return totalTest;
    }

    public void setTotalTest(Long totalTest) {
        this.totalTest = totalTest;
    }

    public String getCollectionFile() {
        return collectionFile;
    }

    public void setCollectionFile(String collectionFile) {
        this.collectionFile = collectionFile;
    }

    public String getEnvironmentFile() {
        return environmentFile;
    }

    public void setEnvironmentFile(String environmentFile) {
        this.environmentFile = environmentFile;
    }

    @Override()
    public String getCetCode() {
        return "PostmanTestConfig";
    }
}
