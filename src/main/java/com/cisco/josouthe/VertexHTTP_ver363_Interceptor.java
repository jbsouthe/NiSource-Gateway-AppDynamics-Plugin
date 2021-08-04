package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.ServletContext;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VertexHTTP_ver363_Interceptor extends MyBaseInterceptor{

    private static final ConcurrentHashMap<Object, TransactionDictionary> transactionsMap = new ConcurrentHashMap<>();
    Scheduler scheduler;
    IReflector getRequest, getRawMethod, getUri, getHost, getHeaders, getHeader, getParams, getScheme, getRemoteAddress, getLocalAddress; //io.vertx.core.http.HttpServerRequest
    IReflector getMultiMapNames, getMultiMapValues; //io.vertx.core.MultiMap
    IReflector getSocketAddressHost, getSocketAddressPort; //io.vertx.core.net.SocketAddress

    public VertexHTTP_ver363_Interceptor() {
        super();
        scheduler = Scheduler.getInstance(30000L, 120000L, transactionsMap);

        //io.vertx.core.http.HttpServerRequest methods:
        getRequest = makeInvokeInstanceMethodReflector("getRequest"); //io.netty.handler.codec.http.DefaultHttpRequest
        getRawMethod = makeInvokeInstanceMethodReflector( "rawMethod"); //String
        getUri = makeInvokeInstanceMethodReflector("uri"); //String
        getHost = makeInvokeInstanceMethodReflector("host"); //String
        getHeaders = makeInvokeInstanceMethodReflector("headers"); //io.vertx.core.MultiMap
        getHeader = makeInvokeInstanceMethodReflector("getHeader", "java.lang.String"); //String
        getParams = makeInvokeInstanceMethodReflector("params"); //io.vertx.core.MultiMap
        getScheme = makeInvokeInstanceMethodReflector("scheme"); //String
        getRemoteAddress = makeInvokeInstanceMethodReflector("remoteAddress"); //io.vertx.core.net.SocketAddress
        getLocalAddress = makeInvokeInstanceMethodReflector("localAddress"); //io.vertx.core.net.SocketAddress

        //io.vertx.core.MultiMap methods:
        getMultiMapNames = makeInvokeInstanceMethodReflector("names"); //Set<String>
        getMultiMapValues = makeInvokeInstanceMethodReflector("getAll", "java.lang.String"); //List<String>

        //io.vertx.core.net.SocketAddress methods:
        getSocketAddressHost = makeInvokeInstanceMethodReflector("host"); //String
        getSocketAddressPort = makeInvokeInstanceMethodReflector("port"); //Integer (autoboxed int)

    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        getLogger().debug(String.format("Begin onMethodBegin %s.%s() ",className,methodName));
        Transaction transaction = null;
        switch (methodName) {
            case "handleBegin": { //start a BT
                transaction = AppdynamicsAgent.getTransaction();
                if (isFakeTransaction(transaction)) {
                    transaction = AppdynamicsAgent.startServletTransaction(buildServletContext(objectIntercepted), EntryTypes.HTTP, getCorrelationHeader(objectIntercepted), true);
                    getLogger().debug(String.format("Transaction not active, started BT: %s", transaction.getUniqueIdentifier()));
                } else {
                    getLogger().debug(String.format("Transaction already active, using BT: %s", transaction.getUniqueIdentifier()));
                }
                transactionsMap.put(objectIntercepted, new TransactionDictionary(transaction, objectIntercepted));
                break;
            }
            case "handler": { //just FYI for now
                getLogger().debug(String.format("Handler called: %s",params[0].toString()));
            }
            case "handleException": { //mark current bt as error
                if( transactionsMap.contains(objectIntercepted) ) {
                    transaction = transactionsMap.get(objectIntercepted).getTransaction();
                    transaction.markAsError(String.format("HTTP Connection Exception: %s", params[0].toString()));
                }
            }
            case "handleContent":
            case "handleEnd": {
                transaction = AppdynamicsAgent.startSegment(objectIntercepted);
                break;
            }
        }
        getLogger().debug(String.format("Finish onMethodBegin %s.%s() ",className,methodName));
        return transaction;
    }

    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;

        getLogger().debug(String.format("Begin onMethodEnd %s.%s() ",className,methodName));
        Transaction transaction = (Transaction) state;
        if( exception != null ) {
            transaction.markAsError(String.format("%s.%s() Exception: %s",className,methodName,exception.getMessage()));
        }
        switch (methodName) {
            case "handleException": { transaction.markAsError(String.format("HTTP Connection Exception: %s", params[0].toString())); }
            case "handleBegin":
            case "handleContent": {
                transaction.endSegment();
                break;
            }
            case "handleEnd": {
                transaction.end();
                if( transactionsMap.contains(object) )
                    transactionsMap.get(object).finish();
                break;
            }
        }
        getLogger().debug(String.format("Finish onMethodEnd %s.%s() ",className,methodName));
    }

    public ServletContext buildServletContext( Object request ) {
        getLogger().debug(String.format("Begin building servlet context"));
        ServletContext.ServletContextBuilder builder = new ServletContext.ServletContextBuilder();

        String scheme = getReflectiveString(request, getScheme, "http");
        String host = getReflectiveString(request, getHost, "UNKNOWN-HOST");
        String uri = getReflectiveString(request, getUri, "/unknown-uri");
        try {
            builder.withURL(String.format("%s://%s/%s",scheme,host,uri));
            getLogger().debug(String.format("Building servlet context with URL: %s://%s/%s",scheme,host,uri));
        } catch (MalformedURLException exception) {
            getLogger().info(String.format("Error building servlet context with URL: %s://%s/%s Exception: %s",scheme,host,uri,exception.toString()));
            return null;
        }

        builder.withRequestMethod( getReflectiveString(request,getRawMethod,"POST"));
        Object localHostSocketAddress = getReflectiveObject(request,getLocalAddress);
        if( localHostSocketAddress != null )
            host = getReflectiveString(localHostSocketAddress,getSocketAddressHost,"UNKNOWNSERVERHOST"); //reuse host var
        builder.withHostValue(host);
        Object remoteHostSocketAddress = getReflectiveObject(request,getRemoteAddress);
        if( remoteHostSocketAddress != null )
            builder.withHostOriginatingAddress( getReflectiveString(remoteHostSocketAddress,getSocketAddressHost,"UNKNOWN-REMOTEHOST"));

        //get headers:
        Object multimapHeaders = getReflectiveObject(request,getHeaders);
        Set<String> names = (Set<String>) getReflectiveObject(multimapHeaders, getMultiMapNames);
        if( multimapHeaders != null && names != null) {
            Map<String,String> appdHeaders = new HashMap<>();
            for( String name : names ) {
                List<String> values = (List<String>) getReflectiveObject(multimapHeaders,getMultiMapValues,name);
                if( values != null && values.size() >0 )
                    appdHeaders.put(name, values.get(0));
            }
            builder.withHeaders(appdHeaders);
        }

        //get parameters:
        Object multimapParameters = getReflectiveObject(request,getParams);
        names = (Set<String>) getReflectiveObject(multimapParameters, getMultiMapNames); //reuse names var
        if( multimapParameters != null && names != null ) {
            Map<String,String[]> appdParams = new HashMap<>();
            for( String name : names ) {
                List<String> values = (List<String>) getReflectiveObject(multimapParameters,getMultiMapValues,name);
                if( values != null && values.size() >0 ) {
                    appdParams.put(name, values.toArray(new String[0]));
                }
            }
            builder.withParameters(appdParams);
        }

        getLogger().debug(String.format("Finish building servlet context"));
        return builder.build();
    }

    public String getCorrelationHeader( Object request ) {
        return (String) getReflectiveObject(request, getHeader, AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER);
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();

        /*
        This should catch: handleBegin,
            handleContent,
            ignore handleData, because called by handleContent,
            ignore handlePipelined, because it will call handleBegin and handleEnd
            ignore handler() for now, it seems to be a chaining method for any content handlers registered, we have another interceptor for that
            handleEnd
            handleException( Exception ) looks interesting

         */
        rules.add(new Rule.Builder(
                "io.vertx.core.http.HttpServerRequest")
                .classMatchType(SDKClassMatchType.IMPLEMENTS_INTERFACE)
                .methodMatchString("handle")
                .methodStringMatchType(SDKStringMatchType.STARTSWITH)
                .build()
        );

        return rules;
    }
}
