package com.cisco.josouthe;

import com.appdynamics.agent.api.*;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.net.MalformedURLException;
import java.util.*;

public class NiSourceRouteHandlerInterceptor extends MyBaseInterceptor {

    IReflector getRequest; //https://vertx.io/docs/apidocs/io/vertx/ext/web/RoutingContext.html#request--
    IReflector getUri, getScheme, getMethod, getParams, getHost, getHeaders, getHeader; //methods on HttpServerRequest
    IReflector getMapNames, getMapEntry; //methods on MultiMap

    public NiSourceRouteHandlerInterceptor() {
        super();

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

        return transaction;
    }

    private String getCorrelationHeader(Object request) {
        return (String) getReflectiveObject(request, getHeader, CORRELATION_HEADER_KEY);
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
        } catch (MalformedURLException e) {
            getLogger().info(String.format("Oops, Bad URL %s Exception: %s",urlSB.toString(),e.getMessage()));
            return null;
        }

        builder.withRequestMethod( getReflectiveString(request,getMethod,"POST") );
        builder.withHostValue( getReflectiveString(request,getHost,"UNKNOWN-HOST") );

        Map<String,String> headers = new HashMap<>();
        Object headersMultiMap = getReflectiveObject(request,getHeaders);
        if( headersMultiMap != null ) {
            for( String name : (Set<String>) getReflectiveObject(headersMultiMap,getMapNames) ){
                headers.put(name, (String) getReflectiveObject(headersMultiMap,getMapEntry,name) );
            }
            builder.withHeaders(headers);
        }

        Map<String,String[]> parameters = new HashMap<>();
        Object parametersMultiMap = getReflectiveObject(request,getParams);
        if( parametersMultiMap != null ) {
            for( String name : (Set<String>) getReflectiveObject(parametersMultiMap,getMapNames) ) {
                parameters.put(name, new String[] { (String) getReflectiveObject(parametersMultiMap,getMapEntry,name) });
            }
            builder.withParameters(parameters);
        }

        return builder.build();
    }


    @Override
    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        Transaction transaction = (Transaction) state;
        if( transaction == null ) return;

        if( exception != null ) transaction.markAsError( exception.getMessage() );
        transaction.end();

    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<>();

        rules.add(new Rule.Builder(
                "com.nisource.endpoints.NisourceRouteHandlersBase")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .withParams("io.vertx.ext.web.RoutingContext")
                .build()
        );
        return rules;
    }
}
