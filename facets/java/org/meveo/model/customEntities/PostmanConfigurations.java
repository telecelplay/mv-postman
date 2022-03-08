package org.meveo.model.customEntities;

import org.meveo.model.CustomEntity;
import java.util.List;
import org.meveo.model.persistence.DBStorageType;
import org.meveo.admin.job.FileProcessingJob;
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

    private String response;

    private FileProcessingJob testField;

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

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public FileProcessingJob getTestField() {
        return testField;
    }

    public void setTestField(FileProcessingJob testField) {
        this.testField = testField;
    }

    @Override()
    public String getCetCode() {
        return "PostmanConfigurations";
    }
}
