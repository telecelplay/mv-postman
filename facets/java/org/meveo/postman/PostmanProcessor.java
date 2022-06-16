package org.meveo.postman;

import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.meveo.model.customEntities.PostmanTestConfig;
import org.meveo.model.customEntities.PostmanTest;
import org.meveo.service.storage.RepositoryService;
import org.meveo.service.crm.impl.CurrentUserProducer;
import org.meveo.service.admin.impl.MeveoModuleService;
import org.meveo.model.storage.Repository;
import org.meveo.api.persistence.CrossStorageApi;
import org.meveo.service.git.GitHelper;
import org.meveo.security.MeveoUser;


import javax.script.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import javax.script.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.time.Instant;

import org.meveo.service.script.Script;
import org.meveo.admin.exception.BusinessException;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;

public class PostmanProcessor extends Script {
	
	private final static Logger log = LoggerFactory.getLogger(PostmanProcessor.class);
  	private CrossStorageApi crossStorageApi = getCDIBean(CrossStorageApi.class);
	private RepositoryService repositoryService = getCDIBean(RepositoryService.class);
    private MeveoModuleService moduleService = getCDIBean(MeveoModuleService.class);
	private Repository defaultRepo = repositoryService.findDefaultRepository();
    private CurrentUserProducer currentUserProducer = getCDIBean(CurrentUserProducer.class);

  	private int totalTest = 0;
	private int failedTest = 0;
	
  	private String environmentFile;
	private String collectionFile;

	public void setEnvironmentFile(String environmentFile){
		this.environmentFile = environmentFile;
	}
	public void setCollectionFile(String collectionFile){
		this.collectionFile = collectionFile;
	}

	public class PSContext {
		Map<String,Object> context;

		public PSContext(Map<String,Object> context){
			this.context=context;
		}

		public Object get(String key) {
			log.debug("getting " + key);
			return context.get(key);
		}

		public void set(String key, Object value) {
			log.info("setting " + key + " to :" + value);
			context.put(key, value);
		}
	}

	private class CookieRegister implements ClientRequestFilter{
		private Map<String,Cookie> cookieMap = new HashMap<>();

		public void addCookiesFromResponse(Response response){
			cookieMap.putAll(response.getCookies());
		}

		@Override
		public void filter(ClientRequestContext clientRequestContext) throws IOException {
			if(cookieMap.size()>0){
				ArrayList<Object> cookie=new ArrayList<>(cookieMap.values());
				clientRequestContext.getHeaders().put("Cookie",cookie );
			}
		}
	}

	private class LoggingFilter implements ClientRequestFilter {
		@Override
		public void filter(ClientRequestContext requestContext) throws IOException {
			if(requestContext.getEntity() != null) {
				log.info(requestContext.getEntity().toString());
				log.info("Headers      : {}", requestContext.getHeaders());
			}
		}
	}

	public void loadEnvironment(String postmanEnv,Map<String, Object> context) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> map = mapper.readValue(postmanEnv, Map.class);
		log.info("load Postman environment "+map.get("name"));
		ArrayList<Object> values = (ArrayList<Object>)map.get("values");
        
		for(Object rawValue : values) {
			Map<String, Object> value = (Map<String, Object>) rawValue;
			Boolean enabled = (Boolean)value.get("enabled");
			if(enabled){
				String key=(String)value.get("key");
				Object val=value.get("value");
				context.put(key,val);
				log.info("Added "+key+" => "+val+ " to context");
			}
		}
	}
  
	@Override
	public void execute(Map<String,Object> parameters) throws BusinessException {
      	
      	
		PostmanRunnerScript runner  = new PostmanRunnerScript();
      
      	try{
          	log.info("collection file path == {}",this.collectionFile);
          	log.info("environment file path == {}",this.environmentFile);
          
            MeveoUser user = currentUserProducer.getCurrentUser();
            java.io.File modulePathDoc = GitHelper.getRepositoryDir(user,"mv-postman");
            if(modulePathDoc == null){
                throw new BusinessException("cannot load postman collection, module directory not found");
            }
            
            String envFilePath = modulePathDoc.getPath()+ this.environmentFile;
            String collFilePath = modulePathDoc.getPath()+ this.collectionFile;
              
            log.info("pm environment file path == "+envFilePath );
            log.info("pm collection file path == "+collFilePath );
              
            String postmanCollection = new String ( Files.readAllBytes( Paths.get( collFilePath )));
          	String postmanEnv = new String ( Files.readAllBytes( Paths.get( envFilePath )));
             
            log.debug("postmanCollection="+postmanCollection);
            log.debug("postmanEnv="+postmanEnv);
            
          	PostmanTestConfig config = new PostmanTestConfig();
          	config.setEnvironmentFile(postmanEnv);
          	config.setCollectionFile(postmanCollection);
          	config.setExecutionDate(Instant.now());
          	String uuId = crossStorageApi.createOrUpdate(defaultRepo, config);	
            config.setUuid(uuId);
            log.info("PostmanTestConfig.uuId = "+config.getUuid());
                      
			runner.setPostmanJsonCollection(postmanCollection);
			Map<String,Object> context = new HashMap<>();
			this.loadEnvironment(postmanEnv,context);            
			runner.runScript(config.getUuid(),context);
          
          
          
		} catch (Exception ex){
			ex.printStackTrace();
		}
	}

	private class PostmanRunnerScript {
		//input
		private String postmanJsonCollection;
		private boolean stopOnError=true;
		private boolean trustAllCertificates;

		//output
		private int totalRequest = 0;
		private int failedRequest = 0;
		private Map<String, Object> context;
      	private String configId;
      
      	private Pattern postmanVarPattern = Pattern.compile("\\{\\{[^\\}]+\\}\\}");
		private ScriptEngine jsEngine;
		private CookieRegister cookieRegister;

		private List<String> failedRequestName = new ArrayList<>();
		private List<String> failedTestName = new ArrayList<>();

		private void runScript(String configId, Map<String, Object> context) throws BusinessException{
			try {
				this.context = context;
              	this.configId = configId;
                ScriptEngineManager scriptManager = new ScriptEngineManager();
              
                log.debug("scriptManager = {}",scriptManager);
              
				jsEngine = scriptManager.getEngineByName("js");
                Context.newBuilder("js")
                        .allowHostAccess(HostAccess.ALL)
                        .allowHostClassLookup(s -> true)
                        .option("js.ecmascript-version", "2021");
              
                log.debug("jsEngine = {}",jsEngine);
              
              
                if (jsEngine == null){    				
    				throw new BusinessException("js not found");
				}
				Bindings bindings = jsEngine.createBindings();
				bindings.put("polyglot.js.allowAllAccess", true);
				context.forEach((k, v) -> {
					if (v instanceof Integer) {
						bindings.put(k, (int) v);
					} else if (v instanceof Double) {
						bindings.put(k, (double) v);
					} else if (v instanceof Boolean) {
						bindings.put(k, (boolean) v);
					} else {
						bindings.put(k, v.toString());//might be better to serialized to json in all cases ?
					}
				});
				bindings.put("context", new PSContext(context));
				ScriptContext scriptContext = new SimpleScriptContext();
				scriptContext.setBindings(bindings, ScriptContext.GLOBAL_SCOPE);
				jsEngine.setContext(scriptContext);

				cookieRegister = new CookieRegister();

				ObjectMapper mapper = new ObjectMapper();
				Map<String, Object> map = mapper.readValue(postmanJsonCollection, Map.class);
				Map<String, Object> info = (Map<String, Object>) map.get("info");
				log.debug("executing collection :" + info.get("name"));
				ArrayList<Object> items = (ArrayList<Object>) map.get("item");                
                //items.forEach(i->log.info(i.toString()));
				executeItemList(items);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		private void executeItemList(ArrayList<Object> items) {
			log.debug("items  :" + items.size());
            log.info("items[0]="+items.get(0));
			for (Object rawItem : items) {
				Map<String, Object> item = (Map<String, Object>) rawItem;
                item.keySet().forEach(k->log.info(k.toString()));
				boolean isSection = item.containsKey("item");
				log.info("executing " + (isSection ? "section" : "test") + " :" + item.get("name"));
				try {
					ArrayList<Object> events = (ArrayList<Object>) item.get("event");
					if (events != null) {
						executeEvent((String) item.get("name"), "prerequest", events);
					}
					if (isSection) {
						ArrayList<Object> itemList = (ArrayList<Object>) item.get("item");
						executeItemList(itemList);
					} else {
						totalRequest++;
						executeItem(item);
					}
                  	
					if (events != null) {
						executeEvent((String) item.get("name"), "test", events);
					}
				} catch (ScriptException e) {
					e.printStackTrace();
					failedRequest++;
					failedRequestName.add((String) item.get("name"));
					if (stopOnError) {
						throw new RuntimeException(e);
					}
				}
			}
		}

		private Object getValueByKey(String key, ArrayList<Object> list) {
			for (Object rawParam : list) {
				Map<String, Object> param = (Map<String, Object>) rawParam;
				if (key.equals(param.get("key"))) {
					return param.get("value");
				}
			}
			return null;
		}


		private void executeItem(Map<String, Object> item) throws ScriptException {
			log.info("executing item :" + item.get("name"));
          	ResteasyClient client = null;
          
			if (trustAllCertificates) {
                client = new ResteasyClientBuilder().disableTrustManager().build();
			}else{
                client = new ResteasyClientBuilder().build();
            }			

			client.register(cookieRegister);
			client.register(new LoggingFilter());

			Map<String, Object> request = (Map<String, Object>) item.get("request");
          	PostmanTest postmanTest = new PostmanTest();

			Map<String, Object> url = (Map<String, Object>) request.get("url");
			String rawUrl = (String) url.get("raw");
			String resolvedUrl = replaceVars(rawUrl);
			System.out.println("calling :" + resolvedUrl);
          	postmanTest.setTestConfigId(this.configId);
          	postmanTest.setEndpoint(resolvedUrl);          

            ResteasyWebTarget target = client.target(resolvedUrl);
			Invocation.Builder requestBuilder = target.request();
			if (request.containsKey("auth")) {
				Map<String, Object> auth = (Map<String, Object>) request.get("auth");
				String authType = (String) auth.get("type");
				switch (authType) {
					case "bearer":
						String token = replaceVars((String) getValueByKey("token", (ArrayList<Object>) auth.get("bearer")));
						log.info("Set bearer token to " + token);
						requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
						break;
					case "basic":                        
						String password = replaceVars((String) getValueByKey("password", (ArrayList<Object>) auth.get("basic")));
						String username = replaceVars((String) getValueByKey("username", (ArrayList<Object>) auth.get("basic")));
                        log.debug("Basic Authentication for userName =" + username+"  and password ="+password);
						byte[] encodedAuth = Base64.encodeBase64((username + ":" + password).getBytes(Charset.forName("ISO-8859-1")));
						requestBuilder.header(HttpHeaders.AUTHORIZATION, "Basic " + new String(encodedAuth));
				}
			}
			if (request.containsKey("header")) {
				ArrayList<Object> headers = (ArrayList<Object>) request.get("header");
				for (Object rawParam : headers) {
					Map<String, Object> param = (Map<String, Object>) rawParam;
					String val = replaceVars((String) param.get("value"));
					log.debug("add header " + param.get("key") + " = " + val);
					requestBuilder.header((String) param.get("key"), val);
				}
			}
			Response response = null;
			if ("GET".equals(request.get("method"))) {
				response = requestBuilder.get();
			} else if ("POST".equals(request.get("method")) || "PUT".equals(request.get("method"))) {
				Entity<?> entity = null;
				Map<String, Object> body = (Map<String, Object>) request.get("body");
				if ("urlencoded".equals(body.get("mode"))) {
                    log.debug("method=POST ");
					ArrayList<Object> formdata = (ArrayList<Object>) body.get("urlencoded");
					Form form = new Form();
					for (Object rawParam : formdata) {
						Map<String, Object> param = (Map<String, Object>) rawParam;
                        log.debug("form parameter key="+((String) param.get("key"))+" and Value assigned = "+replaceVars((String) param.get("value")));
						form.param((String) param.get("key"), replaceVars((String) param.get("value")));
					}
					entity = Entity.form(form);
				} else if ("formdata".equals(body.get("mode"))) {
					ArrayList<Object> formdata = (ArrayList<Object>) body.get(body.get("mode"));
					MultipartFormDataOutput mdo = new MultipartFormDataOutput();
					for (Object rawParam : formdata) {
						Map<String, Object> param = (Map<String, Object>) rawParam;
						if ("file".equals(param.get("type"))) {
							try {
								mdo.addFormData((String) param.get("key"), new FileInputStream(new File(replaceVars((String) param.get("value")))),
										MediaType.APPLICATION_OCTET_STREAM_TYPE);
							} catch (FileNotFoundException e) {
								response.close();
								throw new ScriptException("cannot read file : " + request.get("method"));
							}
						} else {
							MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
							try {
								MediaType.valueOf((String) param.get("contentType"));
							} catch (Exception e) {
								mediaType = MediaType.TEXT_PLAIN_TYPE;
							}
							mdo.addFormData((String) param.get("key"), replaceVars((String) param.get("value")), mediaType);
						}
					}
					entity = Entity.entity(mdo, MediaType.MULTIPART_FORM_DATA_TYPE);
				} else if ("raw".equals(body.get("mode"))) {
					entity = Entity.text(replaceVars((String) body.get("raw")));
				} else if ("file".equals(body.get("mode"))) {
					Map<String, Object> file = (Map<String, Object>) request.get("file");
					MultipartFormDataOutput mdo = new MultipartFormDataOutput();
					try {
						mdo.addFormData("file", new FileInputStream(new File(replaceVars((String) file.get("src")))),
								MediaType.APPLICATION_OCTET_STREAM_TYPE); //NOTE we allow to use variables in the file src
					} catch (FileNotFoundException e) {
						response.close();
						throw new ScriptException("cannot read file : " + request.get("method"));
					}
					entity = Entity.entity(mdo, MediaType.MULTIPART_FORM_DATA_TYPE);
				}
                log.info("Request Body looks like =>"+entity.toString());
				if ("POST".equals(request.get("method"))) {
                    log.debug("Just before making a post call");
					response = requestBuilder.post(entity);
                    log.debug("Just after making a call");
				} else {
					response = requestBuilder.put(entity);
				}
              	postmanTest.setRequestBody(entity.toString());
			} else if ("DELETE".equals(request.get("method"))) {
				response = requestBuilder.delete();
			}
			if (response == null) {
				response.close();
				throw new ScriptException("invalid request type : " + request.get("method"));
			}
			log.info("response status :" + response.getStatus());
          	postmanTest.setResponseStatus(""+response.getStatus());
          	postmanTest.setMethodType((String)request.get("method"));
          	postmanTest.setTestRequestId(this.configId);
          
			jsEngine.getContext().setAttribute("req_status", response.getStatus(), ScriptContext.GLOBAL_SCOPE);
			if (response.getStatus() >= 300) {
				response.close();
				throw new ScriptException("response status " + response.getStatus());
			}
			cookieRegister.addCookiesFromResponse(response);
			String value = response.readEntity(String.class);
			log.info("response  :" + value);
          	postmanTest.setResponse(value);
			response.close();
			jsEngine.getContext().setAttribute("req_response", value, ScriptContext.GLOBAL_SCOPE);
          	try{
              log.info( new StringBuilder("postmanTest:{responseStatus=").append(postmanTest.getResponseStatus()).append(", ")
                       .append("methodType=").append(postmanTest.getMethodType()).append(", ")
                       .append("testRequestId=").append(postmanTest.getTestRequestId()).append(", ")
                       .append("requestBody=").append(postmanTest.getRequestBody()).append(", ")
                       .append("testConfigId=").append(postmanTest.getTestConfigId()).append(", ")
                       .append("endpoint=").append(postmanTest.getEndpoint())
                       .append("}").toString());
              crossStorageApi.createOrUpdate(defaultRepo, postmanTest);
            } catch(Exception ex){
              log.error(ex.getMessage());
            }
		}

		public String replaceVars(String input) {
			StringBuffer result = new StringBuffer();
			Matcher matcher = postmanVarPattern.matcher(input);
			while (matcher.find()) {
				String replacement = "";
				String var = matcher.group(0).substring(2);
				var = var.substring(0, var.length() - 2);
				if (context.containsKey(var)) {
					replacement = context.get(var).toString();
				}
				matcher.appendReplacement(result, replacement);
				log.debug("replaced :" + matcher.group(0) + " by " + replacement);
			}
			matcher.appendTail(result);
			return result.toString();
		}

		public void executeEvent(String itemName, String eventName, ArrayList<Object> events) throws ScriptException {		
          for (Object e : events) {              
				Map<String, Object> event = (Map<String, Object>) e;
                //event.keySet().forEach(k->log.info(k.toString()));
				String listen = (String) (event.get("listen"));
                log.info("listen="+listen);
                log.info("eventName.equals(listen) => "+eventName.equals(listen));
				if (eventName.equals(listen)) {
					Map<String, Object> script = (Map<String, Object>) event.get("script");
					if ("text/javascript".equals(script.get("type"))) {
						log.debug("exec class:" + script.get("exec").getClass());
						ArrayList<Object> exec = (ArrayList<Object>) script.get("exec");
						StringBuilder sb = new StringBuilder();
						for (Object line : exec) {
							sb.append((String) line);
							sb.append("\n");
						}
						String scriptSource = sb.toString();

						String preScript = "var pm={};\n" +
								"pm.info={};\n" +
								"pm.info.eventName='" + eventName + "';\n" +
								"pm.info.iteration=1;\n" +
								"pm.info.iterationCount=1;\n" +
								"pm.info.requestName='" + itemName + "';\n" +
								"pm.info.requestId='" + event.get("id") + "';\n" +
								"pm.environment=context;\n" +
								"pm.test = function(s,f){\n" +
								"var result = null;\n" +
								"try{ result=f(); }\n" +
								"catch(error){throw 'test failed: '+s+' reason: '+error};\n" +
								"if(result != undefined){\n" +
								"if(!result){throw 'test failed: '+s;}" +
								"};\n" +
								"};";
						if ("test".equals(eventName)) {
							preScript += "pm.response = {};\n" +
									"pm.response.text=function(){ return req_response};\n" +
									"pm.response.json=function(){ return JSON.parse(req_response)};" +
									"pm.response.to={};\n" +
									"pm.response.to.have={};\n" +
									"pm.response.to.have.status=function(status){if(status!=req_status){throw 'invalid status'+s}};\n" +
									"pm.response.to.be={};\n" +
									"pm.response.to.be.oneOf=function(status){if(!status.includes(req_status)){throw 'invalid status'+s}};\n";
						}
						scriptSource = preScript + scriptSource;
						log.info("script = " + scriptSource);
						jsEngine.eval(scriptSource);
					}
				}
			}
		}

		public void setStopOnError(boolean stopOnError) {
			this.stopOnError = stopOnError;
		}

		public void setTrustAllCertificates(boolean trustAllCertificates) {
			this.trustAllCertificates = trustAllCertificates;
		}

		public void setPostmanJsonCollection(String postmanJsonCollection) {
			this.postmanJsonCollection = postmanJsonCollection;
		}

		public String getPostmanJsonCollection() {
			return postmanJsonCollection;
		}

		public int getTotalRequest() {
			return totalRequest;
		}

		public int getFailedRequest() {
			return failedRequest;
		}

		public int getTotalTest() {
			return totalTest;
		}

		public int getFailedTest() {
			return failedTest;
		}

		public List<String> getFailedRequestName() {
			return failedRequestName;
		}

		public List<String> getFailedTestName() {
			return failedTestName;
		}
	}
}