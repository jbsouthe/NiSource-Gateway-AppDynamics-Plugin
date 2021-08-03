package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class NiSourceHTTPRequestInterceptor extends MyBaseInterceptor {

    private static final ConcurrentHashMap<Object, TransactionDictionary> transactionsMap = new ConcurrentHashMap<>();
    Scheduler scheduler;
    IReflector getRequest; //https://vertx.io/docs/apidocs/io/vertx/ext/web/RoutingContext.html#request--
    IReflector getUri, getScheme, getMethod, getParams, getHost, getHeaders, getHeader; //methods on HttpServerRequest
    IReflector getMapNames, getMapEntry; //methods on MultiMap

    public NiSourceHTTPRequestInterceptor() {
        super();
        scheduler = Scheduler.getInstance(30000L, 120000L, transactionsMap);
        scheduler.start();

        getRequest = makeInvokeInstanceMethodReflector("request" ); //returns HttpServerRequest object

        getUri = makeInvokeInstanceMethodReflector("uri"); //String
        getScheme = makeInvokeInstanceMethodReflector("scheme"); //String
        getMethod = makeInvokeInstanceMethodReflector("method"); //String
        getParams = makeInvokeInstanceMethodReflector("params"); //MultiMap
        getHost = makeInvokeInstanceMethodReflector("host"); //String
        getHeaders = makeInvokeInstanceMethodReflector("headers"); //MultiMap
        getHeader = makeInvokeInstanceMethodReflector("getHeader", "java.lang.String"); //String

        getMapNames = makeInvokeInstanceMethodReflector("names"); //Set<String>
        getMapEntry = makeInvokeInstanceMethodReflector("get", "java.lang.String");

    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        getLogger().debug(String.format("%s.%s() onMethodBegin firing",className,methodName));
        /*
        Object request = null;
        if( params.length > 0 ) {
            request = getReflectiveObject(params[0], getRequest);
            if( request == null ) {
                getLogger().info(String.format("%s.%s() Oops, Request is not found??? giving up",className,methodName));
                return null;
            }
        } else {
            getLogger().info(String.format("%s.%s() Oops, Parameter not found??? giving up",className,methodName));
            return null;
        }
        Transaction transaction = AppdynamicsAgent.startServletTransaction( buildServletContext(request), EntryTypes.HTTP, getCorrelationHeader(request), false);


         */
        Transaction transaction = null;
        switch (methodName) {
            case "<init>": {
                transaction = AppdynamicsAgent.startTransactionAndServiceEndPoint("BT-" + className, null, className, EntryTypes.POJO, true);
                transactionsMap.put(objectIntercepted, new TransactionDictionary(transaction, objectIntercepted));
                break;
            }
            case "onFailure": //fall through
            case "onSuccess": {
                TransactionDictionary transactionDictionary = transactionsMap.get(objectIntercepted);
                transaction = transactionDictionary.getTransaction();
                break;
            }
            default: {
                getLogger().info(String.format("Unknown method handler: %s.%s()", className, methodName));
                break;
            }
        }
        return transaction;
    }

    /*
    private String getCorrelationHeader(Object request) {
        String correlationHeader = (String) getReflectiveObject(request, getHeader, CORRELATION_HEADER_KEY);
        getLogger().debug(String.format("getCorrelationHeader found header: %s",correlationHeader));
        return correlationHeader;
    }

    private ServletContext buildServletContext(Object request) {
        ServletContext.ServletContextBuilder builder = new ServletContext.ServletContextBuilder();
        StringBuilder urlSB = new StringBuilder();
        urlSB.append( getReflectiveString(request,getScheme,"https") );
        urlSB.append("://");
        urlSB.append( getReflectiveString(request,getHost,"UNKNOWN-HOST") );
        urlSB.append("/");
        urlSB.append( getReflectiveString(request, getUri, "/unknown-uri") );
        try{
            builder.withURL(urlSB.toString());
            getLogger().debug(String.format("buildServletContext with URL: %s",urlSB.toString()));
        } catch (MalformedURLException e) {
            getLogger().info(String.format("Oops, Bad URL %s Exception: %s",urlSB.toString(),e.getMessage()));
            return null;
        }

        builder.withRequestMethod( getReflectiveString(request,getMethod,"POST") );
        builder.withHostValue( getReflectiveString(request,getHost,"UNKNOWN-HOST") );
        if( getLogger().isDebugEnabled() ) {
            getLogger().debug(String.format("buildServletContext with Method: %s",getReflectiveString(request,getMethod,"POST")));
            getLogger().debug(String.format("buildServletContext with Host: %s",getReflectiveString(request,getHost,"UNKNOWN-HOST")));
        }

        Map<String,String> headers = new HashMap<>();
        Object headersMultiMap = getReflectiveObject(request,getHeaders);
        if( headersMultiMap != null ) {
            for( String name : (Set<String>) getReflectiveObject(headersMultiMap,getMapNames) ){
                headers.put(name, (String) getReflectiveObject(headersMultiMap,getMapEntry,name) );
                getLogger().debug(String.format("buildServletContext with Header: %s=%s",name, headers.get(name)));
            }
            builder.withHeaders(headers);
        }

        Map<String,String[]> parameters = new HashMap<>();
        Object parametersMultiMap = getReflectiveObject(request,getParams);
        if( parametersMultiMap != null ) {
            for( String name : (Set<String>) getReflectiveObject(parametersMultiMap,getMapNames) ) {
                parameters.put(name, new String[] { (String) getReflectiveObject(parametersMultiMap,getMapEntry,name) });
                getLogger().debug(String.format("buildServletContext with Parameter: %s=%s",name, parameters.get(name)));
            }
            builder.withParameters(parameters);
        }

        return builder.build();
    }

     */

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        Transaction transaction = (Transaction) state;
        getLogger().debug(String.format("%s.%s() onMethodEnd attempting to end transaction with uid: %s and error: %s",
                className, methodName,
                (transaction == null ? "transaction is null" : transaction.getUniqueIdentifier()),
                (exception == null ? "no exception passed" : exception.getMessage())
        ));
        if( transaction == null ) return;

        if( exception != null ) transaction.markAsError( exception.getMessage() );
        switch (methodName) {
            case "<init>": {
                break;
            }
            case "onFailure": { //fall through
                Throwable throwable = (Throwable) params[0];
                Integer statusCode = (Integer) params[1];
                transaction.markAsError(String.format("onFailure Status Code %s, Exception: %s",statusCode,throwable.getMessage()));
            }
            case "onSuccess": {
                transaction.end();
                transactionsMap.get(object).finish();
                break;
            }
            default: {
                getLogger().info(String.format("Unknown method handler: %s.%s()", className, methodName));
                break;
            }
        }

    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();
        for( String method : new String[]{ "<init>", "onSuccess", "onFailure"} )
            rules.add(new Rule.Builder(
                    "com.nisource.remote.rest.handlers.RestResponseHandler")
                    .classMatchType(SDKClassMatchType.IMPLEMENTS_INTERFACE)
                    .methodMatchString(method)
                    .methodStringMatchType(SDKStringMatchType.EQUALS)
                    .build()
            );
        /* nope
        rules.add(new Rule.Builder(
                "com.nisource.endpoints.NisourceRouteHandlersBase")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString(".*")
                .methodStringMatchType(SDKStringMatchType.REGEX)
                .withParams("io.vertx.ext.web.RoutingContext")
                .build()
        );
        rules.add(new Rule.Builder(
                "com.nisource.endpoints.NisourceRouteHandlersBase")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodStringMatchType(SDKStringMatchType.NOT_EMPTY)
                .withParams("io.vertx.ext.web.RoutingContext")
                .build()
        );
        for( String method : new String[]{"<init>","onSuccess","onFailure"}) {
            rules.add(new Rule.Builder(
                    "com.nisource.remote.rest.handlers.RestResponseHandler")
                    .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                    .methodMatchString(method)
                    .methodStringMatchType(SDKStringMatchType.EQUALS)
                    .build()
            );
        }
        */
        return rules;
    }
}
