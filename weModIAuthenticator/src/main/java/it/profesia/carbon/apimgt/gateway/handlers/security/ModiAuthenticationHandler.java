package it.profesia.carbon.apimgt.gateway.handlers.security;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.cache.CacheConfiguration;
import javax.cache.CacheConfiguration.Duration;

import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.RESTConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.common.gateway.dto.ExtensionType;
import org.wso2.carbon.apimgt.common.gateway.dto.JWTConfigurationDto;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.MethodStats;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.gateway.handlers.ext.listener.ExtensionListenerUtil;
import org.wso2.carbon.apimgt.gateway.handlers.security.APIAuthenticationHandler;
import org.wso2.carbon.apimgt.gateway.handlers.security.APIKeyValidator;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityUtils;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationResponse;
import org.wso2.carbon.apimgt.gateway.handlers.security.Authenticator;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerConfigurationService;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.model.exception.DataLoadingException;
import org.wso2.carbon.metrics.manager.MetricManager;
import org.wso2.carbon.metrics.manager.Timer;

import com.google.gson.JsonObject;

import io.swagger.v3.oas.models.OpenAPI;
import it.profesia.carbon.apimgt.gateway.handlers.logging.ModiLogUtils;
import it.profesia.carbon.apimgt.gateway.handlers.security.authenticator.ModiAuthenticator;
import it.profesia.carbon.apimgt.gateway.handlers.utils.CacheProviderWeModi;
import it.profesia.wemodi.subscriptions.SubscriptionService;

/**
 * Authentication handler for REST APIs exposed in the API gateway. This handler will
 * drop the requests if an authentication failure occurs. But before a message is dropped
 * it looks for a special custom error handler sequence APISecurityConstants.API_AUTH_FAILURE_HANDLER
 * through which the message will be mediated when available. This is a custom extension point
 * provided to the users to handle authentication failures in a deployment specific manner.
 * Once the custom error handler has been invoked, this implementation will further try to
 * respond to the client with a 401 Unauthorized response. If this is not required, the users
 * must drop the message in their custom error handler itself.
 * <p/>
 * If no authentication errors are encountered, this will add some AuthenticationContext
 * information to the request and let it through to the next handler in the chain.
 */
public class ModiAuthenticationHandler extends APIAuthenticationHandler implements ManagedLifecycle {
    private static final Log log = LogFactory.getLog(ModiAuthenticationHandler.class);

    protected ArrayList<Authenticator> authenticators = new ArrayList<>();
    protected boolean isAuthenticatorsInitialized = false;
    private SynapseEnvironment synapseEnvironment;

    private String authorizationHeader;
    private String apiSecurity;
    private String apiLevelPolicy;
    private String certificateInformation;
    private String apiUUID;
    private String apiType = String.valueOf(APIConstants.ApiTypes.API); // Default API Type
    private OpenAPI openAPI;
    private String keyManagers;
    private final String type = ExtensionType.AUTHENTICATION.toString();
    private String securityContextHeader;
    protected APIKeyValidator keyValidator;
    protected boolean isOauthParamsInitialized = false;

	public String getId_auth_rest_01() {
		return id_auth_rest_01;
	}

	public void setId_auth_rest_01(String id_auth_rest_01) {
		this.id_auth_rest_01 = id_auth_rest_01;
	}

	public String getId_auth_rest_02() {
		return id_auth_rest_02;
	}

	public void setId_auth_rest_02(String id_auth_rest_02) {
		this.id_auth_rest_02 = id_auth_rest_02;
	}

	public String getIntegrity_rest_01() {
		return integrity_rest_01;
	}

	public void setIntegrity_rest_01(String integrity_rest_01) {
		this.integrity_rest_01 = integrity_rest_01;
	}
	
	public String getPdnd_auth() {
		return pdnd_auth;
	}

	public void setPdnd_auth(String pdnd_auth) {
		this.pdnd_auth = pdnd_auth;
	}
	
	public String getModi_auth() {
		return modi_auth;
	}

	public void setModi_auth(String modi_auth) {
		this.modi_auth = modi_auth;
	}
	
	public String getPdnd_jwks_url() {
		return pdnd_jwks_url;
	}

	public void setPdnd_jwks_url(String pdnd_jwks_url) {
		this.pdnd_jwks_url = pdnd_jwks_url;
	}
	
	public String getPdnd_api_url() {
		return pdnd_api_url;
	}

	public void setPdnd_api_url(String pdnd_api_url) {
		this.pdnd_api_url = pdnd_api_url;
	}
	
	public String getJwt_header_name() {
		return jwt_header_name;
	}

	public void setJwt_header_name(String jwt_header_name) {
		this.jwt_header_name = jwt_header_name;
	}
	
	public String getAudit_rest_01_pdnd() {
		return audit_rest_01_pdnd;
	}

	public void setAudit_rest_01_pdnd(String audit_rest_01_pdnd) {
		this.audit_rest_01_pdnd = audit_rest_01_pdnd;
	}
	
	public String getAudit_rest_01_modi() {
		return audit_rest_01_modi;
	}

	public void setAudit_rest_01_modi(String audit_rest_01_modi) {
		this.audit_rest_01_modi = audit_rest_01_modi;
	}
	
	public String getAudit_rest_02() {
		return audit_rest_02;
	}

	public void setAudit_rest_02(String audit_rest_02) {
		this.audit_rest_02 = audit_rest_02;
	}
	
	public String getIntegrity_rest_02() {
		return integrity_rest_02;
	}

	public void setIntegrity_rest_02(String integrity_rest_02) {
		this.integrity_rest_02 = integrity_rest_02;
	}
	
	public String getApi_aud() {
		return api_aud;
	}

	public void setApi_aud(String api_aud) {
		this.api_aud = api_aud;
	}
	
	public String getApiName() {
		return apiName;
	}

	public void setApiName(String apiName) {
		this.apiName = apiName;
	}

	private String id_auth_rest_01;
    private String id_auth_rest_02;
    private String integrity_rest_01;
    private String pdnd_auth;
    private String modi_auth;
    private String pdnd_jwks_url;
    
    private String pdnd_api_url;
    
    private String jwt_header_name;
    
    private String audit_rest_01_pdnd;
    private String audit_rest_01_modi;
    private String audit_rest_02;
    private String integrity_rest_02;
    
    private String api_aud;
    private String apiName;

	public static final String MODI_HEADER = "Agid-JWT-Signature";
	public static final String PDND_HEADER = HttpHeaders.AUTHORIZATION;
	public static final String JWS_AUDIT_HEADER = "Agid-JWT-TrackingEvidence";

    public String getApiUUID() {
        return apiUUID;
    }

    public void setApiUUID(String apiUUID) {
        this.apiUUID = apiUUID;
    }

    /**
     * To get the certificates uploaded against particular API.
     *
     * @return the certificates uploaded against particular API.
     */
    public String getCertificateInformation() {
        return certificateInformation;
    }

    /**
     * To set the certificates uploaded against particular API.
     *
     * @param certificateInformation the certificates uplaoded against the API.
     */
    public void setCertificateInformation(String certificateInformation) {
        this.certificateInformation = certificateInformation;
    }

    /**
     * To get the API level tier policy.
     *
     * @return Relevant tier policy related with API level policy.
     */
    public String getAPILevelPolicy() {
        return apiLevelPolicy;
    }

    /**
     * To set the API level tier policy.
     *
     * @param apiLevelPolicy Relevant API level tier policy related with this API.
     */
    public void setAPILevelPolicy(String apiLevelPolicy) {
        this.apiLevelPolicy = apiLevelPolicy;
    }

    /**
     * Get type of the API
     * @return API Type
     */
    public String getApiType() {
        return apiType;
    }

    /**
     * Set type of the API
     * @param apiType API Type
     */
    public void setApiType(String apiType) {
        // Since we currently support only Product APIs as the alternative, set the value to "PRODUCT_API" only if
        // the same value is provided as the type. Else the default value will remain as "API".
        if (APIConstants.ApiTypes.PRODUCT_API.name().equalsIgnoreCase(apiType)) {
            this.apiType = apiType;
        }
    }

    private boolean removeOAuthHeadersFromOutMessage = true;

    public void init(SynapseEnvironment synapseEnvironment) {
        this.synapseEnvironment = synapseEnvironment;
        if (log.isDebugEnabled()) {
            log.debug("Initializing ModI API authentication handler instance");
        }
        initializeAuthenticators();
        /*if (getApiManagerConfigurationService() != null) {
            initOAuthParams();
        }*/
    }

    /**
     * To get the Authorization Header.
     *
     * @return Relevant the Authorization Header of the API request
     */
    public String getAuthorizationHeader() {
        return authorizationHeader;
    }

    /**
     * To set the Authorization Header.
     *
     * @param authorizationHeader the Authorization Header of the API request.
     */
    public void setAuthorizationHeader(String authorizationHeader) {
        this.authorizationHeader = authorizationHeader;
    }

    /**
     * To get the API level security expected for the current API in gateway level.
     *
     * @return API level security related with the current API.
     */
    public String getAPISecurity() {
        return apiSecurity;
    }

    /**
     * To set the API level security of current API.
     *
     * @param apiSecurity Relevant API level security.
     */
    public void setAPISecurity(String apiSecurity) {
        this.apiSecurity = apiSecurity;
    }

    public boolean getRemoveOAuthHeadersFromOutMessage() {
        return removeOAuthHeadersFromOutMessage;
    }

    public void setRemoveOAuthHeadersFromOutMessage(boolean removeOAuthHeadersFromOutMessage) {
        this.removeOAuthHeadersFromOutMessage = removeOAuthHeadersFromOutMessage;
    }

    protected APIManagerConfigurationService getApiManagerConfigurationService() {
        return ServiceReferenceHolder.getInstance().getApiManagerConfigurationService();
    }

    public void destroy() {
        if (keyValidator != null) {
            this.keyValidator.cleanup();
        }
        if (!authenticators.isEmpty()) {
            for (Authenticator authenticator : authenticators) {
                authenticator.destroy();
            }
        } else {
            log.warn("Unable to destroy uninitialized authentication handler instance");
        }
    }

    protected void initOAuthParams() {
        setKeyValidator();
        APIManagerConfiguration config = getApiManagerConfiguration();
        String value = config.getFirstProperty(APIConstants.REMOVE_OAUTH_HEADERS_FROM_MESSAGE);
        if (value != null) {
            removeOAuthHeadersFromOutMessage = Boolean.parseBoolean(value);
        }
        JWTConfigurationDto jwtConfigurationDto = config.getJwtConfigurationDto();
        if (jwtConfigurationDto != null) {
            value = jwtConfigurationDto.getJwtHeader();
        }
        if (value != null) {
            setSecurityContextHeader(value);
        }
        isOauthParamsInitialized = true;
    }

    public void setSecurityContextHeader(String securityContextHeader) {
        this.securityContextHeader = securityContextHeader;
    }

    public String getSecurityContextHeader() {
        return securityContextHeader;
    }

    protected APIManagerConfiguration getApiManagerConfiguration() {
        return ServiceReferenceHolder.getInstance().getAPIManagerConfiguration();
    }

    protected APIKeyValidator getAPIKeyValidator() {
        return this.keyValidator;
    }

    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "LEST_LOST_EXCEPTION_STACK_TRACE", justification = "The exception needs to thrown for fault sequence invocation")
    protected void initializeAuthenticators() {
        isAuthenticatorsInitialized = true;

        /*boolean isOAuthProtected = false;
        boolean isMutualSSLProtected = false;
        boolean isBasicAuthProtected = false;
        boolean isApiKeyProtected = false;
        boolean isMutualSSLMandatory = false;
        boolean isOAuthBasicAuthMandatory = false;

        // Set security conditions
        if (apiSecurity == null) {
            isOAuthProtected = true;
        } else {
            String[] apiSecurityLevels = apiSecurity.split(",");
            for (String apiSecurityLevel : apiSecurityLevels) {
                if (apiSecurityLevel.trim().equalsIgnoreCase(APIConstants.DEFAULT_API_SECURITY_OAUTH2)) {
                    isOAuthProtected = true;
                } else if (apiSecurityLevel.trim().equalsIgnoreCase(APIConstants.API_SECURITY_MUTUAL_SSL)) {
                    isMutualSSLProtected = true;
                } else if (apiSecurityLevel.trim().equalsIgnoreCase(APIConstants.API_SECURITY_BASIC_AUTH)) {
                    isBasicAuthProtected = true;
                } else if (apiSecurityLevel.trim().equalsIgnoreCase(APIConstants.API_SECURITY_MUTUAL_SSL_MANDATORY)) {
                    isMutualSSLMandatory = true;
                } else if (apiSecurityLevel.trim().equalsIgnoreCase(APIConstants.API_SECURITY_OAUTH_BASIC_AUTH_API_KEY_MANDATORY)) {
                    isOAuthBasicAuthMandatory = true;
                } else if (apiSecurityLevel.trim().equalsIgnoreCase((APIConstants.API_SECURITY_API_KEY))) {
                    isApiKeyProtected = true;
                }
            }
        }
        if (!isMutualSSLProtected && !isOAuthBasicAuthMandatory) {
            isOAuthBasicAuthMandatory = true;
        }
        if (!isBasicAuthProtected && !isOAuthProtected && !isMutualSSLMandatory && !isApiKeyProtected) {
            isMutualSSLMandatory = true;
        }

        // Set authenticators
        if (isMutualSSLProtected) {
            Authenticator  authenticator = new MutualSSLAuthenticator(apiLevelPolicy, isMutualSSLMandatory, certificateInformation);
            authenticator.init(synapseEnvironment);
            authenticators.add(authenticator);
        }
        if (isOAuthProtected) {
            Authenticator authenticator = new OAuthAuthenticator(authorizationHeader, isOAuthBasicAuthMandatory,
                    removeOAuthHeadersFromOutMessage);
            authenticator.init(synapseEnvironment);
            authenticators.add(authenticator);
        }
        if (isBasicAuthProtected) {
            Authenticator authenticator = new BasicAuthAuthenticator(authorizationHeader, isOAuthBasicAuthMandatory,
                    apiLevelPolicy);
            authenticator.init(synapseEnvironment);
            authenticators.add(authenticator);
        }
        if (isApiKeyProtected) {
            Authenticator authenticator = new ApiKeyAuthenticator(APIConstants.API_KEY_HEADER_QUERY_PARAM, apiLevelPolicy, isOAuthBasicAuthMandatory);
            authenticator.init(synapseEnvironment);
            authenticators.add(authenticator);
        }
        Authenticator authenticator = new InternalAPIKeyAuthenticator(APIMgtGatewayConstants.INTERNAL_KEY);
		try {
			ModiDBUtil.initialize();
		} catch (APIManagerDatabaseException e) {
			log.fatal("Error while initializing the datasource", e);
		}*/
        Authenticator authenticator = null;
        String modi_jwt = ((modi_jwt = getJwt_header_name()) != null && !(getJwt_header_name().equals("")) && !(getJwt_header_name().contains("additionalProperties"))) ? modi_jwt : MODI_HEADER;
        log.info("modi jwt: "+modi_jwt);
        
        boolean enabledCache = false;
        long expiryTimeInSecondsCache = 0;
        try {
			JsonObject cacheConfigurations = new SubscriptionService().getCacheConfigurations();
			enabledCache = cacheConfigurations.get("enabledCache").getAsBoolean();
			CacheProviderWeModi.setEnabledCache(enabledCache);
			expiryTimeInSecondsCache = Long.parseLong(cacheConfigurations.get("expiryTimeInSecondsCache").getAsString());
			CacheProviderWeModi.setExpiryTimeInSecondsCache(expiryTimeInSecondsCache);
			
			if (CacheProviderWeModi.isEnabledCache() && apiName != null && !(apiName.equals("")) && !(apiName.contains("additionalProperties"))) {
				CacheProviderWeModi.removeModiCache(apiName);
				log.debug("WeModI cache removed");
				CacheProviderWeModi.createWeModiCache(apiName);
				log.debug("WeModI cache created");
				if (log.isDebugEnabled())
					CacheProviderWeModi.listAvailableCaches();
			}
			
		} catch (Exception e) {
			log.error("No cache configurations found", e);
		}
        	
        authenticator = new ModiAuthenticator(modi_jwt, PDND_HEADER, JWS_AUDIT_HEADER);
        authenticator.init(synapseEnvironment);
        authenticators.add(authenticator);
        authenticators.sort(new Comparator<Authenticator>() {
            @Override
            public int compare(Authenticator o1, Authenticator o2) {
                return (o1.getPriority() - o2.getPriority());
            }
        });
    }

    @MethodStats
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "EXS_EXCEPTION_SOFTENING_RETURN_FALSE",
            justification = "Error is sent through payload")
    public boolean handleRequest(MessageContext messageContext) {
    	ModiLogUtils.initialize(messageContext);
    	log.info(ModiLogUtils.EROGAZIONE_START);

        /*TracingSpan keyTracingSpan = null;
        TelemetrySpan keySpan = null;
        if (TelemetryUtil.telemetryEnabled()) {
            TelemetrySpan responseLatencySpan =
                    (TelemetrySpan) messageContext.getProperty(APIMgtGatewayConstants.RESOURCE_SPAN);
            TelemetryTracer tracer = ServiceReferenceHolder.getInstance().getTelemetryTracer();
            keySpan = TelemetryUtil.startSpan(APIMgtGatewayConstants.KEY_VALIDATION, responseLatencySpan, tracer);
            messageContext.setProperty(APIMgtGatewayConstants.KEY_VALIDATION, keySpan);
            org.apache.axis2.context.MessageContext axis2MC =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            axis2MC.setProperty(APIMgtGatewayConstants.KEY_VALIDATION, keySpan);
        } else if (Util.tracingEnabled()) {
            TracingSpan responseLatencySpan =
                    (TracingSpan) messageContext.getProperty(APIMgtGatewayConstants.RESOURCE_SPAN);
            TracingTracer tracer = Util.getGlobalTracer();
            keyTracingSpan = Util.startSpan(APIMgtGatewayConstants.KEY_VALIDATION, responseLatencySpan, tracer);
            messageContext.setProperty(APIMgtGatewayConstants.KEY_VALIDATION, keyTracingSpan);
            org.apache.axis2.context.MessageContext axis2MC =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            axis2MC.setProperty(APIMgtGatewayConstants.KEY_VALIDATION, keyTracingSpan);
        }

        Timer.Context context = startMetricTimer();
        long startTime = System.nanoTime();
        long endTime;
        long difference;

        if (Utils.isGraphQLSubscriptionRequest(messageContext)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping GraphQL subscription handshake request.");
            }
            return true;
        }*/

        try {
            /*if (isAnalyticsEnabled()) {
                long currentTime = System.currentTimeMillis();
                messageContext.setProperty("api.ut.requestTime", Long.toString(currentTime));
            }*/

            messageContext.setProperty(APIMgtGatewayConstants.API_TYPE, apiType);
            
            messageContext.setProperty("ID_AUTH_REST_01", id_auth_rest_01);
            messageContext.setProperty("ID_AUTH_REST_02", id_auth_rest_02);
            messageContext.setProperty("INTEGRITY_REST_01", integrity_rest_01);
            messageContext.setProperty("PDND_AUTH", pdnd_auth);
            messageContext.setProperty("MODI_AUTH", modi_auth);
            messageContext.setProperty("PDND_JWKS_URL", pdnd_jwks_url);
            messageContext.setProperty("PDND_API_URL", pdnd_api_url);
            messageContext.setProperty("AUDIT_REST_01_PDND", audit_rest_01_pdnd);
            messageContext.setProperty("AUDIT_REST_01_MODI", audit_rest_01_modi);
            messageContext.setProperty("AUDIT_REST_02", audit_rest_02);
            messageContext.setProperty("INTEGRITY_REST_02", integrity_rest_02);
            messageContext.setProperty("API_AUD", api_aud);
            
            if (ExtensionListenerUtil.preProcessRequest(messageContext, type)) {
                if (!isAuthenticatorsInitialized) {
                    initializeAuthenticators();
                }
               /* if (!isOauthParamsInitialized) {
                    initOAuthParams();
                }
                String authenticationScheme = getAPIKeyValidator().getResourceAuthenticationScheme(messageContext);
                if(APIConstants.AUTH_NO_AUTHENTICATION.equals(authenticationScheme)) {
                    if(log.isDebugEnabled()){
                        log.debug("Found Authentication Scheme: ".concat(authenticationScheme));
                    }
                    handleNoAuthentication(messageContext);
                    return ExtensionListenerUtil.postProcessRequest(messageContext, type);
                }*/
                try {
                    if (isAuthenticate(messageContext)) {
                        setAPIParametersToMessageContext(messageContext);
                        return ExtensionListenerUtil.postProcessRequest(messageContext, type);
                    }
                } catch (APIManagementException e) {
                    log.error(ModiLogUtils.EROGAZIONE_AUTH_ERROR, e);
                }
            }
        } catch (APISecurityException e) {

           /* if (TelemetryUtil.telemetryEnabled()) {
                TelemetryUtil.setTag(keySpan, APIMgtGatewayConstants.ERROR, APIMgtGatewayConstants.KEY_SPAN_ERROR);
            } else if (Util.tracingEnabled()) {
                Util.setTag(keyTracingSpan, APIMgtGatewayConstants.ERROR, APIMgtGatewayConstants.KEY_SPAN_ERROR);
            }
            if (log.isDebugEnabled()) {
                    // We do the calculations only if the debug logs are enabled. Otherwise this would be an overhead
                    // to all the gateway calls that is happening.
                    endTime = System.nanoTime();
                    difference = (endTime - startTime) / 1000000;
                    String messageDetails = logMessageDetails(messageContext);
                    log.debug("Call to Key Manager : " + messageDetails + ", elapsedTimeInMilliseconds=" +
                            difference / 1000000);
                }*/

                String errorMessage = APISecurityConstants.getAuthenticationFailureMessage(e.getErrorCode());

                if (APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE.equals(errorMessage)) {
                    log.error(ModiLogUtils.EROGAZIONE_AUTH_ERROR + ": "
                            + APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE, e);
                } else {
                    // We do not need to log known authentication failures as errors since these are not product errors.
                    log.warn("API authentication failure due to " + errorMessage);

                    if (log.isDebugEnabled()) {
                        log.debug("API authentication failed with error " + e.getErrorCode(), e);
                    }
                }

                handleAuthFailure(messageContext, e);
                log.info(ModiLogUtils.AuthFailure(messageContext));
        } finally {
            /*if (TelemetryUtil.telemetryEnabled()) {
                TelemetryUtil.finishSpan(keySpan);
            } else if (Util.tracingEnabled()) {
                Util.finishSpan(keyTracingSpan);
            }
            messageContext.setProperty(APIMgtGatewayConstants.SECURITY_LATENCY,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
            stopMetricTimer(context);*/
        	log.info(ModiLogUtils.EROGAZIONE_FINISH);
        	ModiLogUtils.release();
        }
        return false;
    }

    private void handleNoAuthentication(MessageContext messageContext){

        //Using existing constant in Message context removing the additional constant in API Constants
        String clientIP = null;
        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();
        Map<String, String> transportHeaderMap = (Map<String, String>)
                axis2MessageContext.getProperty
                        (org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);

        if (transportHeaderMap != null) {
            clientIP = transportHeaderMap.get(APIMgtGatewayConstants.X_FORWARDED_FOR);
        }

        //Setting IP of the client
        if (clientIP != null && !clientIP.isEmpty()) {
            if (clientIP.indexOf(",") > 0) {
                clientIP = clientIP.substring(0, clientIP.indexOf(","));
            }
        } else {
            clientIP = (String) axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);
        }

        //Create a dummy AuthenticationContext object with hard coded values for Tier and KeyType. This is because we cannot determine the Tier nor Key Type without subscription information..
        AuthenticationContext authContext = new AuthenticationContext();
        authContext.setAuthenticated(true);
        authContext.setTier(APIConstants.UNAUTHENTICATED_TIER);
        //Since we don't have details on unauthenticated tier we setting stop on quota reach true
        authContext.setStopOnQuotaReach(true);
        //Requests are throttled by the ApiKey that is set here. In an unauthenticated scenario, we will use the client's IP address for throttling.
        authContext.setApiKey(clientIP);
        authContext.setKeyType(APIConstants.API_KEY_TYPE_PRODUCTION);
        //This name is hardcoded as anonymous because there is no associated user token
        authContext.setUsername(APIConstants.END_USER_ANONYMOUS);
        authContext.setCallerToken(null);
        authContext.setApplicationName(null);
        authContext.setApplicationId(clientIP); //Set clientIp as application ID in unauthenticated scenario
        authContext.setConsumerKey(null);
        APISecurityUtils.setAuthenticationContext(messageContext, authContext, securityContextHeader);
    }

    protected void stopMetricTimer(Timer.Context context) {
        context.stop();
    }

    protected Timer.Context startMetricTimer() {
        Timer timer = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                APIConstants.METRICS_PREFIX, this.getClass().getSimpleName()));
        return timer.start();
    }

    public void setKeyValidator() {
        this.keyValidator = new APIKeyValidator();
    }

    /**
     * Authenticates the given request using the authenticators which have been initialized.
     *
     * @param messageContext The message to be authenticated
     * @return true if the authentication is successful (never returns false)
     * @throws APISecurityException If an authentication failure or some other error occurs
     */
    protected boolean isAuthenticate(MessageContext messageContext) throws APISecurityException, APIManagementException {
        boolean authenticated = false;
        AuthenticationResponse authenticationResponse;
        List<AuthenticationResponse> authResponses = new ArrayList<>();

        for (Authenticator authenticator : authenticators) {
            authenticationResponse = authenticator.authenticate(messageContext);
            if (authenticationResponse.isMandatoryAuthentication()) {
                // Update authentication status only if the authentication is a mandatory one
                authenticated = authenticationResponse.isAuthenticated();
            }
            if (!authenticationResponse.isAuthenticated()) {
                authResponses.add(authenticationResponse);
            }
            if (!authenticationResponse.isContinueToNextAuthenticator()) {
                break;
            }
        }
        if (!authenticated) {
            Pair<Integer, String> error = getError(authResponses);
            throw new APISecurityException(error.getKey(), error.getValue());
        }
        return true;
    }

    private Pair<Integer, String> getError(List<AuthenticationResponse> authResponses) {
        Pair<Integer, String> error = null;
        boolean isMissingCredentials = false;
        for (AuthenticationResponse authResponse : authResponses) {
            // get error for transport level mandatory auth failure
            if (!authResponse.isContinueToNextAuthenticator()) {
                error = Pair.of(authResponse.getErrorCode(), authResponse.getErrorMessage());
                return error;
            }
            // get error for application level mandatory auth failure
            if (authResponse.isMandatoryAuthentication() &&
                    (authResponse.getErrorCode() != APISecurityConstants.API_AUTH_MISSING_CREDENTIALS)) {
                error = Pair.of(authResponse.getErrorCode(), authResponse.getErrorMessage());
            } else {
                isMissingCredentials = true;
            }
        }
        // finally checks whether it is missing credentials
        if (error == null && isMissingCredentials) {
            error = Pair.of(APISecurityConstants.API_AUTH_MISSING_CREDENTIALS,
                    APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE);
            return error;
        } else if (error == null) {
            // ideally this should not exist
            error = Pair.of(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                    APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE);
        }
        return error;
    }

    protected String getAuthenticatorsChallengeString() {
        StringBuilder challengeString = new StringBuilder();
        if (authenticators != null) {
            for (Authenticator authenticator : authenticators) {
                challengeString.append(authenticator.getChallengeString()).append(", ");
            }
        }
        return challengeString.toString().trim();
    }

    protected boolean isAnalyticsEnabled() {
        return APIUtil.isAnalyticsEnabled();
    }

    @MethodStats
    public boolean handleResponse(MessageContext messageContext) {
    	ModiLogUtils.initialize(messageContext);

    	ModiLogUtils.release();
        if (ExtensionListenerUtil.preProcessResponse(messageContext, type)) {
            return ExtensionListenerUtil.postProcessResponse(messageContext, type);
        }
        return false;

    }

    private void handleAuthFailure(MessageContext messageContext, APISecurityException e) {
        messageContext.setProperty(SynapseConstants.ERROR_CODE, e.getErrorCode());
        messageContext.setProperty(SynapseConstants.ERROR_MESSAGE,
                APISecurityConstants.getAuthenticationFailureMessage(e.getErrorCode()));
        messageContext.setProperty(SynapseConstants.ERROR_EXCEPTION, e);

        Mediator sequence = messageContext.getSequence(APISecurityConstants.API_AUTH_FAILURE_HANDLER);
        
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();

        //Setting error description which will be available to the handler
        String errorDetail = APISecurityConstants.getFailureMessageDetailDescription(e.getErrorCode(), e.getMessage());
        // if custom auth header is configured, the error message should specify its name instead of default value
        if (e.getErrorCode() == APISecurityConstants.API_AUTH_MISSING_CREDENTIALS) {
            errorDetail =
                    APISecurityConstants.getFailureMessageDetailDescription(e.getErrorCode(), e.getMessage()) + "'"
                            + authorizationHeader + " : Bearer ACCESS_TOKEN' or '" + authorizationHeader +
                            " : Basic ACCESS_TOKEN' or 'apikey: API_KEY'" ;
        }
        if (messageContext.getProperty("SOAPValidation") != null) 
        {
            log.info("SOAP fault");
            messageContext.setProperty(SynapseConstants.IS_CLIENT_DOING_REST, false);
            axis2MC.setDoingREST(false);
        }
        else if (e.getErrorCode() == APISecurityConstants.API_AUTH_INVALID_CREDENTIALS) {
            errorDetail =
                    APISecurityConstants.getFailureMessageDetailDescription(e.getErrorCode(), e.getMessage()) + " JWT token is invalid";
        }
        messageContext.setProperty(SynapseConstants.ERROR_DETAIL, errorDetail);

        // By default we send a 401 response back
        // This property need to be set to avoid sending the content in pass-through pipe (request message)
        // as the response.
        axis2MC.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
        try {
            RelayUtils.consumeAndDiscardMessage(axis2MC);
        } catch (AxisFault axisFault) {
            //In case of an error it is logged and the process is continued because we're setting a fault message in the payload.
            log.error("Error occurred while consuming and discarding the message", axisFault);
        }
        axis2MC.setProperty(Constants.Configuration.MESSAGE_TYPE, "application/soap+xml");
        int status;
        if (e.getErrorCode() == APISecurityConstants.API_AUTH_GENERAL_ERROR ||
                e.getErrorCode() == APISecurityConstants.API_AUTH_MISSING_OPEN_API_DEF) {
            status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
        } else if (e.getErrorCode() == APISecurityConstants.API_AUTH_INCORRECT_API_RESOURCE ||
                e.getErrorCode() == APISecurityConstants.API_AUTH_FORBIDDEN ||
                e.getErrorCode() == APISecurityConstants.INVALID_SCOPE) {
            status = HttpStatus.SC_FORBIDDEN;
        } else {
            status = HttpStatus.SC_UNAUTHORIZED;
            Map<String, String> headers =
                    (Map) axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            if (headers != null) {
                headers.put(HttpHeaders.WWW_AUTHENTICATE, getAuthenticatorsChallengeString() +
                        " error=\"invalid_token\"" +
                        ", error_description=\"The provided token is invalid\"");
                axis2MC.setProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS, headers);
            }
        }

        messageContext.setProperty(APIMgtGatewayConstants.HTTP_RESPONSE_STATUS_CODE, status);

        // Invoke the custom error handler specified by the user
        if (sequence != null && !sequence.mediate(messageContext)) {
            // If needed user should be able to prevent the rest of the fault handling
            // logic from getting executed
            return;
        }

        sendFault(messageContext, status);
    }

    protected void sendFault(MessageContext messageContext, int status) {
        Utils.sendFault(messageContext, status);
    }

    private String logMessageDetails(MessageContext messageContext) {
        //TODO: Hardcoded const should be moved to a common place which is visible to org.wso2.carbon.apimgt.gateway.handlers
        String applicationName = (String) messageContext.getProperty(APIMgtGatewayConstants.APPLICATION_NAME);
        String endUserName = (String) messageContext.getProperty(APIMgtGatewayConstants.END_USER_NAME);
        Date incomingReqTime = null;
        org.apache.axis2.context.MessageContext axisMC = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String logMessage = "API call failed reason=API_authentication_failure"; //"app-name=" + applicationName + " " + "user-name=" + endUserName;
        String logID = axisMC.getOptions().getMessageId();
        if (applicationName != null) {
            logMessage = " belonging to appName=" + applicationName;
        }
        if (endUserName != null) {
            logMessage = logMessage + " userName=" + endUserName;
        }
        if (logID != null) {
            logMessage = logMessage + " transactionId=" + logID;
        }
        String userAgent = (String) ((TreeMap) axisMC.getProperty(org.apache.axis2.context.MessageContext
                .TRANSPORT_HEADERS)).get(APIConstants.USER_AGENT);
        if (userAgent != null) {
            logMessage = logMessage + " with userAgent=" + userAgent;
        }
        String accessToken = (String) ((TreeMap) axisMC.getProperty(org.apache.axis2.context.MessageContext
                .TRANSPORT_HEADERS)).get(APIMgtGatewayConstants.AUTHORIZATION);
        if (accessToken != null) {
            logMessage = logMessage + " with accessToken=" + accessToken;
        }
        String requestURI = (String) messageContext.getProperty(RESTConstants.REST_FULL_REQUEST_PATH);
        if (requestURI != null) {
            logMessage = logMessage + " for requestURI=" + requestURI;
        }
        String requestReceivedTime = (String) ((Axis2MessageContext) messageContext).getAxis2MessageContext()
                .getProperty(APIMgtGatewayConstants.REQUEST_RECEIVED_TIME);
        if (requestReceivedTime != null) {
            long reqIncomingTimestamp = Long.parseLong(requestReceivedTime);
            incomingReqTime = new Date(reqIncomingTimestamp);
            logMessage = logMessage + " at time=" + incomingReqTime;
        }

        String remoteIP = (String) axisMC.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);
        if (remoteIP != null) {
            logMessage = logMessage + " from clientIP=" + remoteIP;
        }
        return logMessage;
    }

    protected void setAPIParametersToMessageContext(MessageContext messageContext) {

        AuthenticationContext authContext = getAuthenticationContext(messageContext);
        org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        String consumerKey = "";
        String username = "";
        String applicationName = "";
        String applicationId = "";
        if (authContext != null) {
            consumerKey = authContext.getConsumerKey();
            username = authContext.getUsername();
            applicationName = authContext.getApplicationName();
            applicationId = authContext.getApplicationId();
        }

        String context = (String) messageContext.getProperty(RESTConstants.REST_API_CONTEXT);
        String version = (String) messageContext.getProperty(RESTConstants.SYNAPSE_REST_API_VERSION);

        String apiPublisher = (String) messageContext.getProperty(APIMgtGatewayConstants.API_PUBLISHER);
        //if publisher is null,extract the publisher from the api_version
        if (apiPublisher == null) {
            apiPublisher = GatewayUtils.getApiProviderFromContextAndVersion(messageContext);
        }

        String api = GatewayUtils.getAPINameFromContextAndVersion(messageContext);
        String resource = extractResource(messageContext);
        String method = (String) (axis2MsgContext.getProperty(
                Constants.Configuration.HTTP_METHOD));
        String hostName = APIUtil.getHostAddress();

        messageContext.setProperty(APIMgtGatewayConstants.CONSUMER_KEY, consumerKey);
        messageContext.setProperty(APIMgtGatewayConstants.USER_ID, username);
        messageContext.setProperty(APIMgtGatewayConstants.CONTEXT, context);
        messageContext.setProperty(APIMgtGatewayConstants.API_VERSION, version);
        messageContext.setProperty(APIMgtGatewayConstants.API, api);
        messageContext.setProperty(APIMgtGatewayConstants.VERSION, version);
        messageContext.setProperty(APIMgtGatewayConstants.RESOURCE, resource);
        messageContext.setProperty(APIMgtGatewayConstants.HTTP_METHOD, method);
        messageContext.setProperty(APIMgtGatewayConstants.HOST_NAME, hostName);
        messageContext.setProperty(APIMgtGatewayConstants.API_PUBLISHER, apiPublisher);
        messageContext.setProperty(APIMgtGatewayConstants.APPLICATION_NAME, applicationName);
        messageContext.setProperty(APIMgtGatewayConstants.APPLICATION_ID, applicationId);
    }

    protected AuthenticationContext getAuthenticationContext(MessageContext messageContext) {
        return APISecurityUtils.getAuthenticationContext(messageContext);
    }

    private String extractResource(MessageContext mc) {
        String resource = "/";
        Pattern pattern = Pattern.compile(APIMgtGatewayConstants.RESOURCE_PATTERN);
        Matcher matcher = pattern.matcher((String) mc.getProperty(RESTConstants.REST_FULL_REQUEST_PATH));
        if (matcher.find()) {
            resource = matcher.group(1);
        }
        return resource;
    }

    public String getKeyManagers() {

        return keyManagers;
    }

    public void setKeyManagers(String keyManagers) {
        this.keyManagers = keyManagers;
    }
}
