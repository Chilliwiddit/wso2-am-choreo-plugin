package org.wso2.apim.analytics.impl;

import feign.Feign;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.wso2.apim.analytics.impl.model.GraphQLClient;
import org.wso2.apim.analytics.impl.model.GraphqlQueryModel;
import org.wso2.apim.analytics.impl.model.graphQLResponseClient;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.AnalyticsException;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.AnalyticsforMonetization;
import org.wso2.carbon.apimgt.api.model.MonetizationUsagePublishInfo;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.apimgt.impl.internal.MonetizationDataHolder;
import org.json.simple.JSONObject;
import org.wso2.apim.analytics.impl.model.QueryAPIAccessTokenInterceptor;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPIProduct;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;

import java.text.SimpleDateFormat;
import java.util.*;

import static org.wso2.apim.analytics.impl.ChoreoAnalyticsConstants.*;


public class ChoreoAnalyticsforMonetizationImpl implements AnalyticsforMonetization {

    private static final Log log = LogFactory.getLog(ChoreoAnalyticsforMonetizationImpl.class);
    private static APIManagerConfiguration config = null;
    APIPersistence apiPersistenceInstance;
    Long currentTimestamp;

    /**
     * Gets Usage Data from Analytics Provider
     *
     * @param lastPublishInfo monetization publish info
     * @return usage data from analytics provider
     * @throws AnalyticsException if the action failed
     */
    @Override
    public Object getUsageData(MonetizationUsagePublishInfo lastPublishInfo) throws AnalyticsException {

        boolean useNewQueryAPI = true;

        Date dateobj = new Date();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(ChoreoAnalyticsConstants.TIME_FORMAT);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(ChoreoAnalyticsConstants.TIME_ZONE));
        String toDate = simpleDateFormat.format(dateobj);

        if (config == null) {
            // Retrieve the access token from api manager configurations.
            config = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService().
                    getAPIManagerConfiguration();
        }
        //used for stripe recording
        currentTimestamp = getTimestamp(toDate);
        //The implementation will be improved to use offset date time to get the time zone based on user input
        String formattedToDate = toDate.concat(ChoreoAnalyticsConstants.TIMEZONE_FORMAT);
        String fromDate = simpleDateFormat.format(
                new java.util.Date(lastPublishInfo.getLastPublishTime()));
        //The implementation will be improved to use offset date time to get the time zone based on user input
        String formattedFromDate = fromDate.concat(ChoreoAnalyticsConstants.TIMEZONE_FORMAT);


        String queryApiEndpoint = config.getMonetizationConfigurationDto().getInsightAPIEndpoint();
        String onPremKey = config.getMonetizationConfigurationDto().getAnalyticsAccessToken();
        if (StringUtils.isEmpty(queryApiEndpoint) || StringUtils.isEmpty(onPremKey)) {
            // Since on prem key is required for both query APIs, it has been made mandatory
            throw new AnalyticsException(
                    "Endpoint or analytics access token for the the analytics query api is not configured");
        }

        String accessToken;
        if (MonetizationDataHolder.getInstance().getMonetizationAccessTokenGenerator() != null) {
            accessToken = MonetizationDataHolder.getInstance().getMonetizationAccessTokenGenerator().getAccessToken();
            if (StringUtils.isEmpty(accessToken)) {
                throw new AnalyticsException(
                        "Cannot retrieve access token from the provided token url");
            }
            useNewQueryAPI = true;
        } else {
            accessToken = onPremKey;
            useNewQueryAPI = false;
        }

        JSONObject timeFilter = new JSONObject();
        timeFilter.put(ChoreoAnalyticsConstants.FROM, formattedFromDate);
        timeFilter.put(ChoreoAnalyticsConstants.TO, formattedToDate);

        List<JSONArray> tenantsAndApis = getMonetizedAPIIdsAndTenantDomains();

        if (tenantsAndApis.size() == 2) {
            if (tenantsAndApis.get(1).size() > 0) {
                JSONObject successAPIUsageByAppFilter = new JSONObject();
                successAPIUsageByAppFilter.put(ChoreoAnalyticsConstants.API_ID_COL, tenantsAndApis.get(1));
                successAPIUsageByAppFilter.put(TENANT_DOMAIN_COL, tenantsAndApis.get(0));
                JSONObject variables = new JSONObject();
                variables.put(ChoreoAnalyticsConstants.TIME_FILTER, timeFilter);
                variables.put(ChoreoAnalyticsConstants.API_USAGE_BY_APP_FILTER, successAPIUsageByAppFilter);
                if (useNewQueryAPI) {
                    variables.put(ChoreoAnalyticsConstants.ON_PREM_KEY, onPremKey);
                }
                GraphQLClient graphQLClient =
                        Feign.builder().client(new OkHttpClient()).encoder(new GsonEncoder()).decoder(new GsonDecoder())
                                .logger(new Slf4jLogger())
                                .requestInterceptor(new QueryAPIAccessTokenInterceptor(accessToken))
                                .target(GraphQLClient.class, queryApiEndpoint);
                GraphqlQueryModel queryModel = new GraphqlQueryModel();
                queryModel.setQuery(getGraphQLQueryBasedOnTheOperationMode(useNewQueryAPI));
                queryModel.setVariables(variables);
                graphQLResponseClient usageResponse = graphQLClient.getSuccessAPIsUsageByApplications(queryModel);
                return usageResponse.getData();
            }
        }
        return null;

    }

    /**
     * The method converts the date into timestamp
     *
     * @param date
     * @return Timestamp in long format
     */
    private long getTimestamp(String date) {

        SimpleDateFormat formatter = new SimpleDateFormat(ChoreoAnalyticsConstants.TIME_FORMAT);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        long time = 0;
        Date parsedDate = null;
        try {
            parsedDate = formatter.parse(date);
            time = parsedDate.getTime();
        } catch (java.text.ParseException e) {
            log.error("Error while parsing the date ", e);
        }
        return time;
    }

    /**
     * Returns the list of monetized API Ids with their tenants
     *
     * @return List<JSONArray>
     * @throws AnalyticsException if the action failed
     */
    public List<JSONArray> getMonetizedAPIIdsAndTenantDomains() throws AnalyticsException {

        JSONArray monetizedAPIIdsList = new JSONArray();
        JSONArray tenantDomainList = new JSONArray();
        List<JSONArray> tenantsAndApis = new ArrayList<>(2);
        try {
            Properties properties = new Properties();
            properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
            properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
            Map<String, String> configMap = new HashMap<>();
            Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
            if (configs != null && !configs.isEmpty()) {
                configMap.putAll(configs);
            }
            configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                    Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));

            apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, properties);
            List<Tenant> tenants = APIUtil.getAllTenantsWithSuperTenant();
            for (Tenant tenant : tenants) {
                tenantDomainList.add(tenant.getDomain());
                try {
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(
                            tenant.getDomain(), true);
                    String tenantAdminUsername = APIUtil.getAdminUsername();
                    if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenant.getDomain())) {
                        tenantAdminUsername =
                                APIUtil.getAdminUsername() + ChoreoAnalyticsConstants.AT + tenant.getDomain();
                    }
                    APIProvider apiProviderNew = APIManagerFactory.getInstance().getAPIProvider(tenantAdminUsername);
                    List<API> allowedAPIs = apiProviderNew.getAllAPIs();
                    Organization org = new Organization(tenant.getDomain());
                    for (API api : allowedAPIs) {
                        PublisherAPI publisherAPI = null;
                        try {
                            publisherAPI = apiPersistenceInstance.getPublisherAPI(org, api.getUUID());
                            if (publisherAPI.isMonetizationEnabled()) {
                                monetizedAPIIdsList.add(api.getUUID());
                            }
                        } catch (APIPersistenceException e) {
                            throw new AnalyticsException("Failed to retrieve the API of UUID: " + api.getUUID(), e);
                        }
                    }
                    Map<String, Object> productMap = apiProviderNew.searchPaginatedAPIProducts("", tenant.getDomain(), 0,
                            Integer.MAX_VALUE);
                    if (productMap != null && productMap.containsKey(PRODUCTS)) {
                        SortedSet<APIProduct> productSet = (SortedSet<APIProduct>) productMap.get(PRODUCTS);
                        for (APIProduct apiProduct : productSet) {
                            PublisherAPIProduct publisherAPIProduct;
                            try {
                                publisherAPIProduct = apiPersistenceInstance.getPublisherAPIProduct(org,
                                        apiProduct.getUuid());
                                if (publisherAPIProduct.isMonetizationEnabled()) {
                                    monetizedAPIIdsList.add(apiProduct.getUuid());
                                }
                            } catch (APIPersistenceException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                } catch (APIManagementException e) {
                    throw new AnalyticsException("Error while retrieving the Ids of Monetized APIs");
                }
            }
        } catch (UserStoreException e) {
            throw new AnalyticsException("Error while retrieving the tenants", e);
        }
        tenantsAndApis.add(tenantDomainList);
        tenantsAndApis.add(monetizedAPIIdsList);
        return tenantsAndApis;
    }

    public String getGraphQLQueryBasedOnTheOperationMode(boolean useNewQueryAPI) {

        if (useNewQueryAPI) {
            return "query($onPremKey: String!, $timeFilter: TimeFilter!, " +
                    "$successAPIUsageByAppFilter: SuccessAPIUsageByAppFilter!) " +
                    "{getSuccessAPIsUsageByApplicationsWithOnPremKey(onPremKey:$onPremKey, timeFilter: $timeFilter, " +
                    "successAPIUsageByAppFilter: $successAPIUsageByAppFilter) { apiId apiName apiVersion " +
                    "apiCreatorTenantDomain applicationId applicationName applicationOwner count}}";
        } else {
            return "query($timeFilter: TimeFilter!, " +
                    "$successAPIUsageByAppFilter: SuccessAPIUsageByAppFilter!) " +
                    "{getSuccessAPIsUsageByApplications(timeFilter: $timeFilter, " +
                    "successAPIUsageByAppFilter: $successAPIUsageByAppFilter) { apiId apiName apiVersion " +
                    "apiCreatorTenantDomain applicationId applicationName applicationOwner count}}";
        }
    }


}
