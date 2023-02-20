package org.meveo.model.customEntities;

import org.meveo.model.CustomEntity;
import java.util.List;
import org.meveo.model.persistence.DBStorageType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class PostmanTest implements CustomEntity {

    public PostmanTest() {
    }

    public PostmanTest(String uuid) {
        this.uuid = uuid;
    }

    private String uuid;

    @JsonIgnore()
    private DBStorageType storages;

    private String testConfigId;

    private String endpoint;

    private String methodType;

    private String requestBody;

    private String response;

    @JsonProperty(required = true)
    private String testRequestId;

    private String responseStatus;

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

    public String getTestConfigId() {
        return testConfigId;
    }

    public void setTestConfigId(String testConfigId) {
        this.testConfigId = testConfigId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getMethodType() {
        return methodType;
    }

    public void setMethodType(String methodType) {
        this.methodType = methodType;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(String responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getTestRequestId() {
        return testRequestId;
    }

    public void setTestRequestId(String testRequestId) {
        this.testRequestId = testRequestId;
    }

    @Override()
    public String getCetCode() {
        return "PostmanTest";
    }
}
