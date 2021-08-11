package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NiSourceApiClientExitCallInterceptor extends MyBaseInterceptor{

    //private static final ConcurrentHashMap<Object, TransactionDictionary> transactionsMap = new ConcurrentHashMap<>();
    //Scheduler scheduler;
    IReflector getBasePath; //ApiClient
    IReflector getCode; //ApiException
    IReflector accessOriginalRequestAttribute; //okhttp 2.7.5 com.squareup.okhttp.Call Request object
    IReflector getUrl, getNewBuilder, addHeader, build; //com.squareup.okhttp.Request

    public NiSourceApiClientExitCallInterceptor() {
        super();
        //scheduler = Scheduler.getInstance(30000L, 120000L, transactionsMap);

        getBasePath = makeInvokeInstanceMethodReflector("getBasePath"); //String, this should be the url of the target

        getCode = makeInvokeInstanceMethodReflector("getCode"); //Integer, on ApiException object called during ApiCallback.onFailure( param 0 )

        accessOriginalRequestAttribute = makeAccessFieldValueReflector("originalRequest"); //com.squareup.okhttp.Request

        getUrl = makeInvokeInstanceMethodReflector("url"); //java.net.URL

        getNewBuilder = makeInvokeInstanceMethodReflector("newBuilder"); //Request
        addHeader = makeInvokeInstanceMethodReflector("addHeader", String.class.getCanonicalName(), String.class.getCanonicalName()); //Builder
        build = makeInvokeInstanceMethodReflector("build"); //Request
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        /*
        1 - intercept ApiClient.executeAsync(Call call, final Type returnType, final ApiCallback<T> callback), start exit call and tag callback for finish
        2 - intercept ApiCallback.onFailure(ApiException, response.code(), response.headers().toMultimap())
            and ApiCallback.onSuccess(result, response.code(), response.headers().toMultimap())

         */
        ExitCall exitCall = null;
        switch (methodName) {
            case "executeAsync": {
                Transaction transaction = AppdynamicsAgent.getTransaction();
                if( isFakeTransaction(transaction) ) {
                    getLogger().info(String.format("Oops, No active transaction found, missing BT for exit call!!!"));
                    return null;
                }
                Object request = getReflectiveObject(params[0], accessOriginalRequestAttribute); //first parameter on executeAsync is the okhttpv2.7 Call
                URL targetURL = (URL) getReflectiveObject(request, getUrl);

                Map<String,String> descriptionMap = new HashMap<>();
                descriptionMap.put("host", targetURL.getHost());
                descriptionMap.put("port", String.valueOf(targetURL.getPort()));

                exitCall = transaction.startHttpExitCall(descriptionMap, targetURL, true);
                addCorrelationHeader( request, exitCall.getCorrelationHeader() );
                exitCall.stash(params[2]); //stash the ApiCallback, third parameter, object for later callback
                getLogger().debug(String.format("Stashing ExitCall %s on object %s", exitCall.getCorrelationHeader(), params[2]));
                break;
            }
            case "onFailure": { }
            case "onSuccess": {
                exitCall = AppdynamicsAgent.fetchExitCall(objectIntercepted); //this is the ApiCallback stashed earlier, we will continue it
                break;
            }
        }
        return exitCall;
    }

    private void addCorrelationHeader(Object request, String correlationHeader) {
        getLogger().debug(String.format("Adding Correlation Header to Request: %s",correlationHeader));
        if( request == null || correlationHeader == null ) return; //do nothing
        Object builder = getReflectiveObject(request, getNewBuilder ); //Request.newBuilder()
        builder = getReflectiveObject(builder, addHeader, AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER, correlationHeader); //Request.Builder.addHeader(name, value)
        request = getReflectiveObject(builder, build); //Request.Builder.build() returns a Request, let's try and swap the one we have for this new one
        getLogger().debug(String.format("Added  Correlation Header to Request: %s",correlationHeader));
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return; //nothing to do
        ExitCall exitCall = (ExitCall) state;
        switch (methodName) {
            case "executeAsync": {
                break;
            }
            case "onFailure": {
                int responseCode = getReflectiveInteger(params[0], getCode, -1);
                Throwable apiException = (Throwable) params[0];
                AppdynamicsAgent.getTransaction().markAsError(String.format("ExitCall Error Code: %d Error Message: %s",responseCode,apiException.getMessage()));
                getLogger().debug(String.format("Marking Transaction %s with Error: %s", AppdynamicsAgent.getTransaction().getUniqueIdentifier() , apiException.getMessage()));
            }
            case "onSuccess": {
                exitCall.end();
                getLogger().debug(String.format("Ending ExitCall %s", exitCall.getCorrelationHeader()));
                break;
            }
        }
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();
        /*
        From: com.nisource.remote.rest.generated.ApiClient
        import com.nisource.remote.rest.generated.ApiCallback;
        import com.squareup.okhttp.Call;
        import com.squareup.okhttp.Callback;
        import java.lang.reflect.Type;

        public <T> void executeAsync(Call call, final Type returnType, final ApiCallback<T> callback) {
            call.enqueue(new Callback(){

                public void onFailure(Request request, IOException e) {
                    callback.onFailure(new ApiException(e), 0, null);
                }

                public void onResponse(Response response) throws IOException {
                    Object result;
                    try {
                        result = ApiClient.this.handleResponse(response, returnType);
                    }
                    catch (ApiException e) {
                        callback.onFailure(e, response.code(), response.headers().toMultimap());
                        return;
                    }
                    callback.onSuccess(result, response.code(), response.headers().toMultimap());
                }
            });
        }
         */
        rules.add( new Rule.Builder(
                "com.nisource.remote.rest.generated.ApiClient")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("executeAsync")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .withParams("com.squareup.okhttp.Call", "java.lang.reflect.Type", "com.nisource.remote.rest.generated.ApiCallback")
                .build()
        );
        rules.add( new Rule.Builder(
                "com.nisource.remote.rest.generated.ApiClient")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("executeAsync")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .withParams("com.squareup.okhttp.Call", "java.lang.reflect.Type", "com.nisource.remote.rest.generated.ApiCallback")
                .build()
        );

        /*
        From: com.nisource.remote.rest.generated.ApiCallback<T>
        public interface ApiCallback<T> {
            public void onFailure(ApiException var1, int var2, Map<String, List<String>> var3);

            public void onSuccess(T var1, int var2, Map<String, List<String>> var3);

            public void onUploadProgress(long var1, long var3, boolean var5);

            public void onDownloadProgress(long var1, long var3, boolean var5);
        }
         */
        for( String method : new String[]{"onSuccess", "onFailure"})
            rules.add(new Rule.Builder(
                    "com.nisource.remote.rest.generated.ApiCallback")
                    .classMatchType(SDKClassMatchType.IMPLEMENTS_INTERFACE)
                    .methodMatchString(method)
                    .methodStringMatchType(SDKStringMatchType.EQUALS)
                    .build()
            );
        return rules;
    }
}
