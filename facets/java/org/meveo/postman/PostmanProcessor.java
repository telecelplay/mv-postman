package org.meveo.postman;

import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.meveo.model.customEntities.PostmanTestConfig;
import org.meveo.model.customEntities.PostmanTest;
import org.meveo.service.storage.RepositoryService;
import org.meveo.model.storage.Repository;
import org.meveo.api.persistence.CrossStorageApi;

import javax.script.*;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
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

public class PostmanProcessor extends Script {
	
	private final static Logger log = LoggerFactory.getLogger(PostmanProcessor.class);
  	private CrossStorageApi crossStorageApi = getCDIBean(CrossStorageApi.class);
	private RepositoryService repositoryService = getCDIBean(RepositoryService.class);
	private Repository defaultRepo = repositoryService.findDefaultRepository();

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
			log.info(requestContext.getEntity().toString());
			log.info("Headers      : {}", requestContext.getHeaders());
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
				log.debug("Added "+key+" => "+val+ " to context");
			}
		}
	}
  
	@Override
	public void execute(Map<String,Object> parameters) throws BusinessException {
      	
      	//if(args.length<2){
        //    log.warn("usage : java -jar meveoman.jar <collectionFilename> <environmentFilename> [trustAllCertificates]");
        //    System.out.println("  <collectionFilename>: filename (including path) of the postman 2.1 collection json file.");
        //    System.out.println("  <environmentFilename>: filename (including path) the postmane environment json file to load and initialize the context.");
        //    System.out.println("  trustAllCertificates: if this param is set then ssl certificates are not checked.");
        //}
      
        //PostmanRunnerScript runner  = new PostmanRunnerScript();
        //System.out.println("Load collection "+args[0]+ " and init context with env file "+args[1]);
        //try {
            //String postmanCollection = new String ( Files.readAllBytes( Paths.get(args[0]) ) );
            //runner.setPostmanJsonCollection(postmanCollection);
            //runner.setStopOnError(true);
            //if(args.length==3 && "trustAllCertificates".equals(args[2])) {
            //    runner.setTrustAllCertificates(true);
            //}
            //Map<String,Object> context = new HashMap<>();
            //runner.loadEnvironment(args[1],context);
            //runner.execute(context);
        //} catch (IOException e) {
        //    e.printStackTrace();
        //}
		PostmanRunnerScript runner  = new PostmanRunnerScript();
      	try{
          	log.info("collection file path == {}",this.collectionFile);
          	log.info("environment file path == {}",this.environmentFile);
			String postmanCollection = new String ( Files.readAllBytes( Paths.get(this.collectionFile)));
          	String postmanEnv = new String ( Files.readAllBytes( Paths.get(this.environmentFile) ) );
			
          	PostmanTestConfig config = new PostmanTestConfig();
          	config.setEnvironmentFile(postmanEnv);
          	config.setCollectionFile(postmanCollection);
          	config.setExecutionDate(Instant.now());
          	crossStorageApi.createOrUpdate(defaultRepo, config);	
          
			runner.setPostmanJsonCollection(postmanCollection);
			Map<String,Object> context = new HashMap<>();
			this.loadEnvironment(postmanEnv,context);
			runner.runScript(config.getUuid(),context);
		} catch (IOException ex){
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

		private void runScript(String configId, Map<String, Object> context) {
			try {
				this.context = context;
              	this.configId = configId;
				jsEngine = new ScriptEngineManager().getEngineByName("graal.js");
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
				log.info("executing collection :" + info.get("name"));
				ArrayList<Object> items = (ArrayList<Object>) map.get("item");
				executeItemList(items);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		private void executeItemList(ArrayList<Object> items) {
			log.debug("items  :" + items.size());
			for (Object rawItem : items) {
				Map<String, Object> item = (Map<String, Object>) rawItem;
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
			log.debug("executing item :" + item.get("name"));
			ResteasyClientBuilder builder = new ResteasyClientBuilderImpl();
			if (trustAllCertificates) {
				builder.disableTrustManager();
			}
			Client client = builder.build();

			client.register(cookieRegister);
			client.register(new LoggingFilter());

			Map<String, Object> request = (Map<String, Object>) item.get("request");
          	PostmanTest postmanTest = new PostmanTest();

			Map<String, Object> url = (Map<String, Object>) request.get("url");
			String rawUrl = (String) url.get("raw");
			String resolvedUrl = replaceVars(rawUrl);
			System.out.println("calling :" + resolvedUrl);
          	postmanTest.setTestConfigId(configId);
          	postmanTest.setEndpoint(resolvedUrl);
          
			WebTarget target = client.target(resolvedUrl);
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
						byte[] encodedAuth = Base64.encodeBase64((username + ":" + password).getBytes(Charset.forName("ISO-8859-1")));
						requestBuilder.header(HttpHeaders.AUTHORIZATION, "Basic " + new String(encodedAuth));
				}
			}
			if (request.containsKey("header")) {
				ArrayList<Object> headers = (ArrayList<Object>) request.get("header");
				for (Object rawParam : headers) {
					Map<String, Object> param = (Map<String, Object>) rawParam;
					String val = replaceVars((String) param.get("value"));
					log.info("add header " + param.get("key") + " = " + val);
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
					ArrayList<Object> formdata = (ArrayList<Object>) body.get("urlencoded");
					Form form = new Form();
					for (Object rawParam : formdata) {
						Map<String, Object> param = (Map<String, Object>) rawParam;
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
				if ("POST".equals(request.get("method"))) {
					response = requestBuilder.post(entity);
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
			log.debug("response status :" + response.getStatus());
          	postmanTest.setResponseStatus(""+response.getStatus());
          	postmanTest.setMethodType((String)request.get("method"));
          
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
				String listen = (String) (event.get("listen"));
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

						String preSecript = "var pm={};\n" +
								"pm.info={};\n" +
								"pm.info.eventName='" + eventName + "';\n" +
								"pm.info.iteration=1;\n" +
								"pm.info.iterationCount=1;\n" +
								"pm.info.requestName='" + itemName + "';\n" +
								"pm.info.requestId='" + event.get("id") + "';\n" +
								"pm.environment=context;\n" +
								"pm.test = function(s,f){\n" +
								"let result = null;\n" +
								"try{ result=f(); }\n" +
								"catch(error){throw 'test failed: '+s+' reason: '+error};\n" +
								"if(result != undefined){;\n" +
								"if(!result){throw 'test failed: '+s;}" +
								"};\n" +
								"};";
						if ("test".equals(eventName)) {
							preSecript += "pm.response = {};\n" +
									"pm.response.text=function(){ return req_response};\n" +
									"pm.response.json=function(){ return JSON.parse(req_response)};" +
									"pm.response.to={};\n" +
									"pm.response.to.have={};\n" +
									"pm.response.to.have.status=function(status){if(status!=req_status){throw 'invalid status'+s}};\n" +
									"pm.response.to.be={};\n" +
									"pm.response.to.be.oneOf=function(status){if(!status.includes(req_status)){throw 'invalid status'+s}};\n";
						}
						scriptSource = preSecript + scriptSource;
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