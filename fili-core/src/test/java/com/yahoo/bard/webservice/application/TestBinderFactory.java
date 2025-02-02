// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.application;

import static com.yahoo.bard.webservice.data.config.names.TestApiDimensionName.COLOR;
import static com.yahoo.bard.webservice.data.config.names.TestApiDimensionName.SHAPE;
import static com.yahoo.bard.webservice.data.config.names.TestApiDimensionName.SIZE;
import static com.yahoo.bard.webservice.data.config.names.TestDruidTableName.HOURLY;
import static com.yahoo.bard.webservice.data.config.names.TestDruidTableName.MONTHLY;

import com.yahoo.bard.webservice.async.jobs.jobrows.DefaultJobField;
import com.yahoo.bard.webservice.async.jobs.jobrows.DefaultJobRowBuilder;
import com.yahoo.bard.webservice.async.jobs.jobrows.JobRowBuilder;
import com.yahoo.bard.webservice.async.jobs.stores.ApiJobStore;
import com.yahoo.bard.webservice.async.preresponses.stores.PreResponseStore;
import com.yahoo.bard.webservice.async.workflows.AsynchronousWorkflowsBuilder;
import com.yahoo.bard.webservice.async.workflows.TestAsynchronousWorkflowsBuilder;
import com.yahoo.bard.webservice.config.BardFeatureFlag;
import com.yahoo.bard.webservice.data.cache.DataCache;
import com.yahoo.bard.webservice.data.cache.HashDataCache;
import com.yahoo.bard.webservice.data.cache.StubDataCache;
import com.yahoo.bard.webservice.data.cache.TestDataCache;
import com.yahoo.bard.webservice.data.cache.TestTupleDataCache;
import com.yahoo.bard.webservice.data.config.ConfigurationLoader;
import com.yahoo.bard.webservice.data.config.ResourceDictionaries;
import com.yahoo.bard.webservice.data.config.dimension.DimensionConfig;
import com.yahoo.bard.webservice.data.config.dimension.TestDimensions;
import com.yahoo.bard.webservice.data.config.metric.MetricLoader;
import com.yahoo.bard.webservice.data.config.table.TableLoader;
import com.yahoo.bard.webservice.data.dimension.DimensionDictionary;
import com.yahoo.bard.webservice.data.metric.LogicalMetric;
import com.yahoo.bard.webservice.data.metric.MetricDictionary;
import com.yahoo.bard.webservice.data.metric.protocol.protocols.ReaggregationProtocol;
import com.yahoo.bard.webservice.data.volatility.DefaultingVolatileIntervalsService;
import com.yahoo.bard.webservice.data.volatility.NoVolatileIntervalsFunction;
import com.yahoo.bard.webservice.data.volatility.VolatileIntervalsFunction;
import com.yahoo.bard.webservice.data.volatility.VolatileIntervalsService;
import com.yahoo.bard.webservice.druid.client.DruidWebService;
import com.yahoo.bard.webservice.druid.model.orderby.OrderByColumn;
import com.yahoo.bard.webservice.metadata.DataSourceMetadataService;
import com.yahoo.bard.webservice.metadata.QuerySigningService;
import com.yahoo.bard.webservice.metadata.SegmentIntervalsHashIdGenerator;
import com.yahoo.bard.webservice.metadata.TestDataSourceMetadataService;
import com.yahoo.bard.webservice.models.druid.client.impl.TestDruidWebService;
import com.yahoo.bard.webservice.table.PhysicalTable;
import com.yahoo.bard.webservice.table.PhysicalTableDictionary;
import com.yahoo.bard.webservice.util.SimplifiedIntervalList;
import com.yahoo.bard.webservice.web.apirequest.DataApiRequest;
import com.yahoo.bard.webservice.web.apirequest.generator.Generator;
import com.yahoo.bard.webservice.web.apirequest.generator.LegacyGenerator;
import com.yahoo.bard.webservice.web.apirequest.generator.having.DefaultHavingApiGenerator;
import com.yahoo.bard.webservice.web.apirequest.generator.having.HavingGenerator;
import com.yahoo.bard.webservice.web.apirequest.generator.having.PerRequestDictionaryHavingGenerator;
import com.yahoo.bard.webservice.web.apirequest.generator.having.antlr.AntlrHavingGenerator;
import com.yahoo.bard.webservice.web.apirequest.generator.metric.ApiRequestLogicalMetricBinder;
import com.yahoo.bard.webservice.web.apirequest.generator.metric.ProtocolLogicalMetricGenerator;
import com.yahoo.bard.webservice.web.apirequest.generator.orderBy.AntlrOrderByGenerator;
import com.yahoo.bard.webservice.web.apirequest.metrics.ApiMetricAnnotater;
import com.yahoo.bard.webservice.web.endpoints.JobsEndpointResources;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.glassfish.hk2.api.TypeLiteral;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bard test app configuration binder.
 *
 * TODO we can possibly make a provider version of this class for easy test level custom bindings that get wiped on
 * each run.
 */
public class TestBinderFactory extends AbstractBinderFactory {

    public LinkedHashSet<DimensionConfig> dimensionConfig;
    public MetricLoader metricLoader;
    public TableLoader tableLoader;
    public ApplicationState state;

    public boolean afterBindingHookWasCalled = false;
    public boolean afterRegistrationHookWasCalled = false;

    /**
     * Constructor.
     */
    public TestBinderFactory() {
        dimensionConfig = new TestDimensions().getDimensionConfigurationsByApiName(SIZE, COLOR, SHAPE);
        metricLoader = new MetricLoader() {
            @Override
            public void loadMetricDictionary(MetricDictionary dictionary, DimensionDictionary dimensionDictionary) {
                // Empty
            }
        };
        tableLoader = dictionaries -> {
            // Empty
        };
        state = new ApplicationState();
        state.webService = new TestDruidWebService("default");
        state.metadataWebService = new TestDruidWebService("default metadata service");
    }

    /**
     * Constructor.
     *
     * @param dimensionConfig  Set of dimension configs to load
     * @param metricLoader  Loader for the test metrics
     * @param tableLoader  Loader for the test tables
     * @param state  Testing application state
     */
    public TestBinderFactory(
            LinkedHashSet<DimensionConfig> dimensionConfig,
            MetricLoader metricLoader,
            TableLoader tableLoader,
            ApplicationState state
    ) {
        this.dimensionConfig = dimensionConfig;
        this.state = state;
        this.metricLoader = metricLoader;
        this.tableLoader = tableLoader;
    }

    /*
     * Returns a `VolatileIntervalsService` that provides some test volatility intervals.
     * <p>
     *  The service provides volatile intervals for only two tables:
     *  {@link com.yahoo.bard.webservice.data.config.names.TestDruidTableName#MONTHLY}, and
     *  {@link com.yahoo.bard.webservice.data.config.names.TestDruidTableName#HOURLY}. The HOURLY table is volatile
     *  from August 15 2016 to August 16 2016, while the MONTHLY table is volatile from August 1 2016 to
     *  September 1 2016.
     *
     * @return A VolatileIntervalsService that provides some test volatility for the HOURLY and MONTHLY tables
     */
    @Override
    protected VolatileIntervalsService getVolatileIntervalsService() {
        PhysicalTableDictionary physicalTableDictionary = getConfigurationLoader().getPhysicalTableDictionary();
        Map<PhysicalTable, VolatileIntervalsFunction> hourlyMonthlyVolatileIntervals = new LinkedHashMap<>();

        if (physicalTableDictionary.containsKey(HOURLY.asName())) {
            hourlyMonthlyVolatileIntervals.put(
                    getConfigurationLoader().getPhysicalTableDictionary().get(HOURLY.asName()),
                    () -> new SimplifiedIntervalList(
                            Collections.singleton(
                                    new Interval(new DateTime(2016, 8, 15, 0, 0), new DateTime(2016, 8, 16, 0, 0))
                            )
                    )
            );
        }
        if (physicalTableDictionary.containsKey(MONTHLY.asName())) {
            hourlyMonthlyVolatileIntervals.put(
                    getConfigurationLoader().getPhysicalTableDictionary().get(MONTHLY.asName()),
                    () -> new SimplifiedIntervalList(
                            Collections.singleton(
                                    new Interval(new DateTime(2016, 8, 1, 0, 0), new DateTime(2016, 9, 1, 0, 0))
                            )
                    )
            );
        }

        return new DefaultingVolatileIntervalsService(
                NoVolatileIntervalsFunction.INSTANCE,
                hourlyMonthlyVolatileIntervals
        );
    }

    /**
     * Get the query signing service for the test.
     *
     * @return the query signing service
     */
    public QuerySigningService<?> getQuerySigningService() {
        return buildQuerySigningService(
                getConfigurationLoader().getPhysicalTableDictionary(),
                getDataSourceMetadataService()
        );
    }

    @Override
    protected DataSourceMetadataService getDataSourceMetadataService() {
        if (Objects.isNull(dataSourceMetadataService)) {
            super.dataSourceMetadataService = new TestDataSourceMetadataService();
        }
        return dataSourceMetadataService;
    }

    /**
     * Creates an object that generates map of Api Having from having string.
     * Constructs a {@link DefaultHavingApiGenerator} by default.
     * @param loader  Configuration loader that connects resource dictionaries with the loader.
     *
     * @return An object to generate having maps from having string.
     */
    @Override
    protected HavingGenerator buildHavingGenerator(ConfigurationLoader loader) {
        return new PerRequestDictionaryHavingGenerator(new AntlrHavingGenerator(loader.getMetricDictionary()));
    }

    @Override
    public ApiJobStore buildApiJobStore() {
        return JobsEndpointResources.getApiJobStore();
    }

    @Override
    public PreResponseStore buildPreResponseStore(ResourceDictionaries resourceDictionaries) {
        return JobsEndpointResources.getPreResponseStore();
    }

    @Override
    protected JobRowBuilder buildJobRowBuilder() {
        return new DefaultJobRowBuilder(
                jobMetadata -> jobMetadata.get(DefaultJobField.USER_ID) + UUID.randomUUID().toString(),
                ignored -> "greg",
                Clock.systemDefaultZone()
        );
    }

    @Override
    protected Class<? extends AsynchronousWorkflowsBuilder> getAsynchronousProcessBuilder() {
        return TestAsynchronousWorkflowsBuilder.class;
    }

    @Override
    protected MetricLoader getMetricLoader() {
        return metricLoader;
    }

    @Override
    protected LinkedHashSet<DimensionConfig> getDimensionConfigurations() {
        return dimensionConfig;
    }

    @Override
    protected TableLoader getTableLoader() {
        return tableLoader;
    }

    @Override
    protected DruidWebService buildDruidWebService(ObjectMapper mapper) {
        return state.webService;
    }

    @Override
    protected DruidWebService buildMetadataDruidWebService(ObjectMapper mapper) {
        return state.metadataWebService;
    }

    @Override
    protected QuerySigningService<?> buildQuerySigningService(
            PhysicalTableDictionary physicalTableDictionary,
            DataSourceMetadataService dataSourceMetadataService
    ) {
        return state.querySigningService == null ?
                super.buildQuerySigningService(physicalTableDictionary, dataSourceMetadataService) :
                (SegmentIntervalsHashIdGenerator) state.querySigningService;
    }

    @Override
    protected DataCache<?> buildCache() {
        if (BardFeatureFlag.DRUID_CACHE.isOn()) {
            // test cache stored in memory
            if (BardFeatureFlag.DRUID_CACHE_V2.isOn()) {
                state.cache = new TestTupleDataCache();
            } else {
                state.cache = new HashDataCache<>(new TestDataCache());
            }
        } else {
            state.cache = new StubDataCache<>();
        }

        return state.cache;
    }

    protected void bindMetricGenerator(AbstractBinder binder) {
        List<String> protocols = new ArrayList<>();
        TypeLiteral<List<String>> stringListLiteral = new TypeLiteral<List<String>>() { };

        protocols.add(ReaggregationProtocol.REAGGREGATION_CONTRACT_NAME);

        // If you want to configure your system metrics, change the list of metrics
        binder.bind(protocols).named(NAME_ACTIVE_PROTOCOLS).to(stringListLiteral);

        // If you want to make your own business logic, you can change the Annotater implementation
        binder.bind(ApiMetricAnnotater.NO_OP_ANNOTATER).to(ApiMetricAnnotater.class);

        // This is the default binder used in ProtocolDataApiRequestImp
        ProtocolLogicalMetricGenerator protocolLogicalMetricGenerator = new ProtocolLogicalMetricGenerator(
                ApiMetricAnnotater.NO_OP_ANNOTATER,
                protocols
        );

        // The binder used for factories
        TypeLiteral<Generator<LinkedHashSet<LogicalMetric>>> metricGeneratorType =
                new TypeLiteral<Generator<LinkedHashSet<LogicalMetric>>>() { };
        binder.bind(protocolLogicalMetricGenerator).named(NAME_METRIC_GENERATOR).to(metricGeneratorType);

        // The binding used for construction based ApiRequest objects
        binder.bind(protocolLogicalMetricGenerator).to(ApiRequestLogicalMetricBinder.class);

        TypeLiteral<LegacyGenerator<List<OrderByColumn>>> orderByGeneratorType =
                new TypeLiteral<LegacyGenerator<List<OrderByColumn>>>() { };

        binder.bind(AntlrOrderByGenerator.class)
                .named(DataApiRequest.ORDER_BY_GENERATOR_NAMESPACE)
                .to(orderByGeneratorType);
    }
    /**
     * Get the data cache that has been loaded, or build one if none has been loaded yet.
     *
     * @return the data cache being used or that will be used if needed
     */
    protected DataCache<?> getCache() {
        return state.cache == null ? buildCache() : state.cache;
    }

    @Override
    public void afterRegistration(ResourceConfig resourceConfig) {
        afterRegistrationHookWasCalled = true;
    }

    @Override
    protected void afterBinding(AbstractBinder abstractBinder) {
        afterBindingHookWasCalled = true;
    }
}
