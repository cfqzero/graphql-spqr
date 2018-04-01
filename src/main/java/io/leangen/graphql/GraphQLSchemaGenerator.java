package io.leangen.graphql;

import graphql.relay.Relay;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import io.leangen.geantyref.GenericTypeReflector;
import io.leangen.graphql.annotations.GraphQLNonNull;
import io.leangen.graphql.execution.GlobalEnvironment;
import io.leangen.graphql.extension.ExtensionProvider;
import io.leangen.graphql.extension.GraphQLSchemaProcessor;
import io.leangen.graphql.generator.BuildContext;
import io.leangen.graphql.generator.OperationMapper;
import io.leangen.graphql.generator.OperationRepository;
import io.leangen.graphql.generator.OperationSource;
import io.leangen.graphql.generator.OperationSourceRepository;
import io.leangen.graphql.generator.RelayMappingConfig;
import io.leangen.graphql.generator.TypeRepository;
import io.leangen.graphql.generator.mapping.AbstractTypeAdapter;
import io.leangen.graphql.generator.mapping.ArgumentInjector;
import io.leangen.graphql.generator.mapping.ArgumentInjectorRepository;
import io.leangen.graphql.generator.mapping.ConverterRepository;
import io.leangen.graphql.generator.mapping.InputConverter;
import io.leangen.graphql.generator.mapping.OutputConverter;
import io.leangen.graphql.generator.mapping.TypeMapper;
import io.leangen.graphql.generator.mapping.TypeMapperRepository;
import io.leangen.graphql.generator.mapping.common.ArrayAdapter;
import io.leangen.graphql.generator.mapping.common.ByteArrayToBase64Adapter;
import io.leangen.graphql.generator.mapping.common.CollectionOutputConverter;
import io.leangen.graphql.generator.mapping.common.ContextInjector;
import io.leangen.graphql.generator.mapping.common.EnumMapToObjectTypeAdapter;
import io.leangen.graphql.generator.mapping.common.EnumMapper;
import io.leangen.graphql.generator.mapping.common.EnvironmentInjector;
import io.leangen.graphql.generator.mapping.common.IdAdapter;
import io.leangen.graphql.generator.mapping.common.InputValueDeserializer;
import io.leangen.graphql.generator.mapping.common.InterfaceMapper;
import io.leangen.graphql.generator.mapping.common.ListMapper;
import io.leangen.graphql.generator.mapping.common.NonNullMapper;
import io.leangen.graphql.generator.mapping.common.ObjectScalarAdapter;
import io.leangen.graphql.generator.mapping.common.ObjectTypeMapper;
import io.leangen.graphql.generator.mapping.common.OptionalAdapter;
import io.leangen.graphql.generator.mapping.common.OptionalDoubleAdapter;
import io.leangen.graphql.generator.mapping.common.OptionalIntAdapter;
import io.leangen.graphql.generator.mapping.common.OptionalLongAdapter;
import io.leangen.graphql.generator.mapping.common.PageMapper;
import io.leangen.graphql.generator.mapping.common.RootContextInjector;
import io.leangen.graphql.generator.mapping.common.ScalarMapper;
import io.leangen.graphql.generator.mapping.common.StreamToCollectionTypeAdapter;
import io.leangen.graphql.generator.mapping.common.UnionInlineMapper;
import io.leangen.graphql.generator.mapping.common.UnionTypeMapper;
import io.leangen.graphql.generator.mapping.common.VoidToBooleanTypeAdapter;
import io.leangen.graphql.generator.mapping.core.CompletableFutureMapper;
import io.leangen.graphql.generator.mapping.core.DataFetcherResultMapper;
import io.leangen.graphql.generator.mapping.core.PublisherMapper;
import io.leangen.graphql.generator.mapping.strategy.AbstractInputHandler;
import io.leangen.graphql.generator.mapping.strategy.AnnotatedInterfaceStrategy;
import io.leangen.graphql.generator.mapping.strategy.AutoScanAbstractInputHandler;
import io.leangen.graphql.generator.mapping.strategy.DefaultImplementationDiscoveryStrategy;
import io.leangen.graphql.generator.mapping.strategy.ImplementationDiscoveryStrategy;
import io.leangen.graphql.generator.mapping.strategy.InterfaceMappingStrategy;
import io.leangen.graphql.generator.mapping.strategy.NoOpAbstractInputHandler;
import io.leangen.graphql.metadata.exceptions.TypeMappingException;
import io.leangen.graphql.metadata.strategy.DefaultInclusionStrategy;
import io.leangen.graphql.metadata.strategy.InclusionStrategy;
import io.leangen.graphql.metadata.strategy.query.AnnotatedResolverBuilder;
import io.leangen.graphql.metadata.strategy.query.BeanResolverBuilder;
import io.leangen.graphql.metadata.strategy.query.DefaultOperationBuilder;
import io.leangen.graphql.metadata.strategy.query.OperationBuilder;
import io.leangen.graphql.metadata.strategy.query.ResolverBuilder;
import io.leangen.graphql.metadata.strategy.type.DefaultTypeInfoGenerator;
import io.leangen.graphql.metadata.strategy.type.DefaultTypeTransformer;
import io.leangen.graphql.metadata.strategy.type.TypeInfoGenerator;
import io.leangen.graphql.metadata.strategy.type.TypeTransformer;
import io.leangen.graphql.metadata.strategy.value.InputFieldDiscoveryStrategy;
import io.leangen.graphql.metadata.strategy.value.ScalarDeserializationStrategy;
import io.leangen.graphql.metadata.strategy.value.ValueMapper;
import io.leangen.graphql.metadata.strategy.value.ValueMapperFactory;
import io.leangen.graphql.util.ClassUtils;
import io.leangen.graphql.util.Defaults;
import io.leangen.graphql.util.GraphQLUtils;
import io.leangen.graphql.util.Urls;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static graphql.schema.GraphQLObjectType.newObject;
import static java.util.Collections.addAll;

/**
 * <p>This class is the main entry point to the library. It is used to generate a GraphQL schema by analyzing the registered classes
 * and exposing the chosen methods as GraphQL queries or mutations. The process of choosing the methods to expose is delegated
 * to {@link ResolverBuilder} instances, and a different set of builders can be attached to each registered class.
 * One such coupling of a registered class and a set of builders is modeled by an instance of {@link OperationSource}.
 * Methods of the {@code with*OperationSource} family are used to register sources to be analyzed.</p>
 * <p>Builders can also be registered globally (to be used when none are provided explicitly) via {@link #withResolverBuilders(ResolverBuilder...)}.
 * The process of mapping the Java methods to GraphQL queries/mutations will also transparently map all encountered Java types
 * to corresponding GraphQL types. The entire mapping process is handled by an instance {@link OperationMapper} where actual type
 * mapping is delegated to different instances of {@link TypeMapper}.</p>
 * <p>To customize the mapping process, clients can registers their own {@link TypeMapper}s using {@link #withTypeMappers(TypeMapper...)}.
 * Runtime conversion between values provided by the GraphQL client and those expected by Java code might be needed.
 * This is handled by {@link InputConverter} instances.</p>
 * <p>Similarly, the conversion between values returned by Java code and those expected by the GraphQL client (if needed) is
 * handled by {@link OutputConverter} instances.
 * Custom implementations of both {@link InputConverter} and {@link OutputConverter} can be provided using
 * {@link #withInputConverters(InputConverter[])} and {@link #withOutputConverters(OutputConverter[])} respectively.</p>
 *
 * <p><b>Example:</b></p>
 *
 * <pre>
 * {@code
 * UserService userService = new UserService(); //could also be injected by a framework
 * GraphQLSchema schema = new GraphQLSchemaGenerator()
 *      .withOperationsFromSingletons(userService) //register an operations source and use the default strategy
 *      .withNestedResolverBuildersForType(User.class, new BeanResolverBuilder()) //customize how queries are extracted from User.class
 *      .generate();
 * GraphQL graphQL = new GraphQL(schema);
 *
 * //keep the reference to GraphQL instance and execute queries against it.
 * //this query selects a user by ID and requests name and regDate fields only
 * ExecutionResult result = graphQL.execute(
 * "{ user (id: 123) {
 *      name,
 *      regDate
 *  }}");
 *  }
 * </pre>
 */
@SuppressWarnings("WeakerAccess")
public class GraphQLSchemaGenerator {

    private InterfaceMappingStrategy interfaceStrategy = new AnnotatedInterfaceStrategy(true);
    private ScalarDeserializationStrategy scalarStrategy;
    private AbstractInputHandler abstractInputHandler = new NoOpAbstractInputHandler();
    private OperationBuilder operationBuilder = new DefaultOperationBuilder(DefaultOperationBuilder.TypeInference.NONE);
    private ValueMapperFactory<?> valueMapperFactory;
    private InputFieldDiscoveryStrategy inputFieldStrategy;
    private InclusionStrategy inclusionStrategy;
    private ImplementationDiscoveryStrategy implDiscoveryStrategy = new DefaultImplementationDiscoveryStrategy();
    private TypeInfoGenerator typeInfoGenerator = new DefaultTypeInfoGenerator();
    private TypeTransformer typeTransformer = new DefaultTypeTransformer(false, false);
    private GlobalEnvironment environment;
    private String[] basePackages;
    private List<TypeMapper> typeMappers;
    private boolean respectJavaDeprecation = true;
    private final OperationSourceRepository operationSourceRepository = new OperationSourceRepository();
    private final Set<ExtensionProvider<TypeMapper>> typeMapperProviders = new LinkedHashSet<>();
    private final Set<ExtensionProvider<InputConverter>> inputConverterProviders = new LinkedHashSet<>();
    private final Set<ExtensionProvider<OutputConverter>> outputConverterProviders = new LinkedHashSet<>();
    private final Set<ExtensionProvider<ArgumentInjector>> argumentInjectorProviders = new LinkedHashSet<>();
    private final Set<ExtensionProvider<ResolverBuilder>> resolverBuilderProviders = new LinkedHashSet<>();
    private final Set<ExtensionProvider<ResolverBuilder>> nestedResolverBuilderProviders = new LinkedHashSet<>();
    private final Collection<GraphQLSchemaProcessor> processors = new HashSet<>();
    private final RelayMappingConfig relayMappingConfig = new RelayMappingConfig();
    private final Set<GraphQLType> additionalTypes = new HashSet<>();

    private final String queryRoot;
    private final String mutationRoot;
    private final String subscriptionRoot;

    /**
     * Default constructor
     */
    public GraphQLSchemaGenerator() {
        this("Query", "Mutation", "Subscription");
    }

    /**
     * Constructor which allows to customize names of root types.
     * @param queryRoot name of query root type
     * @param mutationRoot name of mutation root type
     * @param subscriptionRoot name of subscription root type
     */
    public GraphQLSchemaGenerator(String queryRoot, String mutationRoot, String subscriptionRoot) {
        this.queryRoot = queryRoot;
        this.mutationRoot = mutationRoot;
        this.subscriptionRoot = subscriptionRoot;
    }

    /**
     * Register {@code serviceSingleton} as a singleton {@link OperationSource},
     * with its class (obtained via {@link Object#getClass()}) as its runtime type and with the globally registered
     * {@link ResolverBuilder}s.
     * All query/mutation methods discovered by analyzing the {@code serviceSingleton}'s type will be later,
     * in query resolution time, invoked on this specific instance (hence the 'singleton' in the method name).
     * Instances of stateless service classes are commonly registered this way.
     *
     * @param serviceSingleton The singleton bean whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingleton(Object serviceSingleton) {
        checkType(serviceSingleton.getClass());
        return withOperationsFromSingleton(serviceSingleton, serviceSingleton.getClass());
    }

    /**
     * Register {@code serviceSingleton} as a singleton {@link OperationSource},
     * with {@code beanType} as its runtime type and with the globally registered {@link ResolverBuilder}s.
     * <p>See {@link #withOperationsFromSingleton(Object)}</p>
     *
     * @param serviceSingleton The singleton bean whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     * @param beanType Runtime type of {@code serviceSingleton}. Should be explicitly provided when it differs from its class
     *                 (that can be obtained via {@link Object#getClass()}). This is commonly the case when the class is generic
     *                 or when the instance has been proxied by a framework.
     *                 Use {@link io.leangen.geantyref.TypeToken} to get a {@link Type} literal
     *                 or {@link io.leangen.geantyref.TypeFactory} to create it dynamically.
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingleton(Object serviceSingleton, Type beanType) {
        return withOperationsFromSingleton(serviceSingleton, GenericTypeReflector.annotate(beanType));
    }

    /**
     * Same as {@link #withOperationsFromSingleton(Object, Type)}, except that an {@link AnnotatedType} is used as
     * {@code serviceSingleton}'s runtime type. Needed when type annotations such as {@link GraphQLNonNull}
     * not directly declared on the class should be captured.
     *
     * @param serviceSingleton The singleton bean whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     * @param beanType Runtime type of {@code serviceSingleton}. Should be explicitly provided when it differs from its class
     *                 (that can be obtained via {@link Object#getClass()}) and when annotations on the type should be kept.
     *                 Use {@link io.leangen.geantyref.TypeToken} to get an {@link AnnotatedType} literal
     *                 or {@link io.leangen.geantyref.TypeFactory} to create it dynamically.
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingleton(Object serviceSingleton, AnnotatedType beanType) {
        checkType(beanType);
        this.operationSourceRepository.registerOperationSource(serviceSingleton, beanType);
        return this;
    }

    /**
     * Same as {@link #withOperationsFromSingleton(Object)} except that custom {@link ResolverBuilder}s will be used
     * to look through {@code beanType} for methods to be exposed.
     *
     * @param serviceSingleton The singleton bean whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     * @param builders Custom strategy to use when analyzing {@code beanType}
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingleton(Object serviceSingleton, ResolverBuilder... builders) {
        return withOperationsFromSingleton(serviceSingleton, serviceSingleton.getClass(), builders);
    }

    /**
     * Same as {@link #withOperationsFromSingleton(Object, Type)} except that custom {@link ResolverBuilder}s will be used
     * to look through {@code beanType} for methods to be exposed.
     *
     * @param serviceSingleton The singleton bean whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     * @param beanType Runtime type of {@code serviceSingleton}. Should be explicitly provided when it differs from its class
     *                 (that can be obtained via {@link Object#getClass()}). This is commonly the case when the class is generic
     *                 or when the instance has been proxied by a framework.
     *                 Use {@link io.leangen.geantyref.TypeToken} to get a {@link Type} literal
     *                 or {@link io.leangen.geantyref.TypeFactory} to create it dynamically.
     * @param builders Custom strategy to use when analyzing {@code beanType}
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingleton(Object serviceSingleton, Type beanType, ResolverBuilder... builders) {
        checkType(beanType);
        return withOperationsFromSingleton(serviceSingleton, GenericTypeReflector.annotate(beanType), builders);
    }

    /**
     * Same as {@link #withOperationsFromSingleton(Object, AnnotatedType)} except that custom {@link ResolverBuilder}s will be used
     * to look through {@code beanType} for methods to be exposed.
     *
     * @param serviceSingleton The singleton bean whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     * @param beanType Runtime type of {@code serviceSingleton}. Should be explicitly provided when it differs from its class
     *                 (that can be obtained via {@link Object#getClass()}) and when annotations on the type should be kept.
     *                 Use {@link io.leangen.geantyref.TypeToken} to get an {@link AnnotatedType} literal
     *                 or {@link io.leangen.geantyref.TypeFactory} to create it dynamically.
     * @param builders Custom builders to use when analyzing {@code beanType}
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingleton(Object serviceSingleton, AnnotatedType beanType, ResolverBuilder... builders) {
        checkType(beanType);
        this.operationSourceRepository.registerOperationSource(serviceSingleton, beanType, Arrays.asList(builders));
        return this;
    }

    /**
     * Same as {@link #withOperationsFromSingleton(Object)} except that multiple beans can be registered at the same time.
     *
     * @param serviceSingletons Singleton beans whose type is to be scanned for query/mutation methods and on which
     *                        those methods will be invoked in query/mutation execution time
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOperationsFromSingletons(Object... serviceSingletons) {
        Arrays.stream(serviceSingletons).forEach(this::withOperationsFromSingleton);
        return this;
    }

    public GraphQLSchemaGenerator withOperationsFromType(Type serviceType) {
        return this.withOperationsFromType(GenericTypeReflector.annotate(serviceType));
    }

    public GraphQLSchemaGenerator withOperationsFromType(Type serviceType, ResolverBuilder... builders) {
        return this.withOperationsFromType(GenericTypeReflector.annotate(serviceType), builders);
    }

    public GraphQLSchemaGenerator withOperationsFromTypes(Type... serviceType) {
        Arrays.stream(serviceType).forEach(this::withOperationsFromType);
        return this;
    }

    public GraphQLSchemaGenerator withOperationsFromType(AnnotatedType serviceType) {
        checkType(serviceType);
        this.operationSourceRepository.registerOperationSource(serviceType);
        return this;
    }

    public GraphQLSchemaGenerator withOperationsFromType(AnnotatedType serviceType, ResolverBuilder... builders) {
        checkType(serviceType);
        this.operationSourceRepository.registerOperationSource(serviceType, Arrays.asList(builders));
        return this;
    }

    public GraphQLSchemaGenerator withOperationsFromTypes(AnnotatedType... serviceType) {
        Arrays.stream(serviceType).forEach(this::withOperationsFromType);
        return this;
    }

    /**
     * Register a type to be scanned for exposed methods, using the globally registered builders.
     * This is not normally required as domain types will be discovered dynamically and globally registered builders
     * will be used anyway. Only needed when no exposed method refers to this domain type directly
     * (relying exclusively on interfaces or super-types instead) and the type should still be mapped and listed in the resulting schema.
     *
     * @param types The domain types that are to be scanned for query/mutation methods
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withNestedOperationsFromTypes(Type... types) {
        Arrays.stream(types).forEach(this::withNestedResolverBuildersForType);
        return this;
    }

    /**
     * The same as {@link #withNestedOperationsFromTypes(Type...)} except that an {@link AnnotatedType} is used,
     * so any extra annotations on the type (not only those directly on the class) are kept.
     *
     * @param types The domain types that are to be scanned for query/mutation methods
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withNestedOperationsFromTypes(AnnotatedType... types) {
        Arrays.stream(types).forEach(this::withNestedResolverBuildersForType);
        return this;
    }

    /**
     * Register {@code querySourceType} type to be scanned for exposed methods, using the provided {@link ResolverBuilder}s.
     * Domain types are discovered dynamically, when referred to by an exposed method (either as its parameter type or return type).
     * This method gives a way to customize how the discovered domain type will be analyzed.
     *
     * @param querySourceType The domain type that is to be scanned for query/mutation methods
     * @param resolverBuilders Custom resolverBuilders to use when analyzing {@code querySourceType} type
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withNestedResolverBuildersForType(Type querySourceType, ResolverBuilder... resolverBuilders) {
        return withNestedResolverBuildersForType(GenericTypeReflector.annotate(querySourceType), resolverBuilders);
    }

    /**
     * Same as {@link #withNestedResolverBuildersForType(Type, ResolverBuilder...)} except that an {@link AnnotatedType} is used
     * so any extra annotations on the type (not only those directly on the class) are kept.
     *
     * @param querySourceType The annotated domain type that is to be scanned for query/mutation methods
     * @param resolverBuilders Custom resolverBuilders to use when analyzing {@code querySourceType} type
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withNestedResolverBuildersForType(AnnotatedType querySourceType, ResolverBuilder... resolverBuilders) {
        this.operationSourceRepository.registerNestedOperationSource(querySourceType, Arrays.asList(resolverBuilders));
        return this;
    }

    /**
     * Globally registers {@link ResolverBuilder}s to be used for sources that don't have explicitly assigned builders.
     *
     * @param resolverBuilders builders to be globally registered
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withResolverBuilders(ResolverBuilder... resolverBuilders) {
        this.resolverBuilderProviders.add(fixedConfig(resolverBuilders));
        return this;
    }

    public GraphQLSchemaGenerator withResolverBuilders(ExtensionProvider<ResolverBuilder> provider) {
        this.resolverBuilderProviders.add(provider);
        return this;
    }

    /**
     * Globally registers {@link ResolverBuilder}s to be used for sources that don't have explicitly assigned builders.
     *
     * @param resolverBuilders builders to be globally registered
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withNestedResolverBuilders(ResolverBuilder... resolverBuilders) {
        this.nestedResolverBuilderProviders.add(fixedConfig(resolverBuilders));
        return this;
    }

    public GraphQLSchemaGenerator withNestedResolverBuilders(ExtensionProvider<ResolverBuilder> provider) {
        this.nestedResolverBuilderProviders.add(provider);
        return this;
    }

    public GraphQLSchemaGenerator withAbstractInputTypeResolution() {
        this.abstractInputHandler = new AutoScanAbstractInputHandler();
        return this;
    }

    public GraphQLSchemaGenerator withAbstractInputHandler(AbstractInputHandler abstractInputHandler) {
        this.abstractInputHandler = abstractInputHandler;
        return this;
    }

    public GraphQLSchemaGenerator withBasePackages(String... basePackages) {
        this.basePackages = basePackages;
        return this;
    }

    public GraphQLSchemaGenerator withJavaDeprecationRespected(boolean respectJavaDeprecation) {
        this.respectJavaDeprecation = respectJavaDeprecation;
        return this;
    }

    public GraphQLSchemaGenerator withTypeInfoGenerator(TypeInfoGenerator typeInfoGenerator) {
        this.typeInfoGenerator = typeInfoGenerator;
        return this;
    }

    public GraphQLSchemaGenerator withValueMapperFactory(ValueMapperFactory<?> valueMapperFactory) {
        this.valueMapperFactory = valueMapperFactory;
        return this;
    }

    public GraphQLSchemaGenerator withInputFieldDiscoveryStrategy(InputFieldDiscoveryStrategy inputFieldStrategy) {
        this.inputFieldStrategy = inputFieldStrategy;
        return this;
    }

    public GraphQLSchemaGenerator withInterfaceMappingStrategy(InterfaceMappingStrategy interfaceStrategy) {
        this.interfaceStrategy = interfaceStrategy;
        return this;
    }

    public GraphQLSchemaGenerator withScalarDeserializationStrategy(ScalarDeserializationStrategy scalarStrategy) {
        this.scalarStrategy = scalarStrategy;
        return this;
    }

    public GraphQLSchemaGenerator withInclusionStrategy(InclusionStrategy inclusionStrategy) {
        this.inclusionStrategy = inclusionStrategy;
        return this;
    }

    public GraphQLSchemaGenerator withImplementationDiscoveryStrategy(ImplementationDiscoveryStrategy implDiscoveryStrategy) {
        this.implDiscoveryStrategy = implDiscoveryStrategy;
        return this;
    }

    public GraphQLSchemaGenerator withTypeTransformer(TypeTransformer transformer) {
        this.typeTransformer = transformer;
        return this;
    }

    /**
     * Registers custom {@link TypeMapper}s to be used for mapping Java type to GraphQL types.
     * <p><b>Ordering of mappers is strictly important as the first {@link TypeMapper} that supports the given Java type
     * will be used for mapping it.</b></p>
     * <p>See {@link TypeMapper#supports(AnnotatedType)}</p>
     *
     * @param typeMappers Custom type mappers to register with the builder
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withTypeMappers(TypeMapper... typeMappers) {
        this.typeMapperProviders.add(fixedConfig(typeMappers));
        return this;
    }

    /**
     * Registers custom {@link TypeMapper}s to be used for mapping Java type to GraphQL types.
     * <p><b>Ordering of mappers is strictly important as the first {@link TypeMapper} that supports the given Java type
     * will be used for mapping it.</b></p>
     * <p>See {@link TypeMapper#supports(AnnotatedType)}</p>
     *
     * @param provider Provides the customized list of TypeMappers to use
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withTypeMappers(ExtensionProvider<TypeMapper> provider) {
        this.typeMapperProviders.add(provider);
        return this;
    }

    /**
     * Registers custom {@link InputConverter}s to be used for converting values provided by the GraphQL client
     * into those expected by the corresponding Java method. Only needed in some specific cases when usual deserialization
     * isn't enough, for example, when a client-provided {@link java.util.List} should be repackaged into a {@link java.util.Map},
     * which is normally done because GraphQL type system has no direct support for maps.
     * <p><b>Ordering of converters is strictly important as the first {@link InputConverter} that supports the given Java type
     * will be used for converting it.</b></p>
     * <p>See {@link InputConverter#supports(AnnotatedType)}</p>
     *
     * @param inputConverters Custom input converters to register with the builder
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withInputConverters(InputConverter<?,?>... inputConverters) {
        this.inputConverterProviders.add(fixedConfig(inputConverters));
        return this;
    }

    public GraphQLSchemaGenerator withInputConverters(ExtensionProvider<InputConverter> provider) {
        this.inputConverterProviders.add(provider);
        return this;
    }

    /**
     * Registers custom {@link OutputConverter}s to be used for converting values returned by the exposed Java method
     * into those expected by the GraphQL client. Only needed in some specific cases when usual serialization isn't enough,
     * for example, when an instance of {@link java.util.Map} should be repackaged into a {@link java.util.List}, which
     * is normally done because GraphQL type system has no direct support for maps.
     * <p><b>Ordering of converters is strictly important as the first {@link OutputConverter} that supports the given Java type
     * will be used for converting it.</b></p>
     * <p>See {@link OutputConverter#supports(AnnotatedType)}</p>
     *
     * @param outputConverters Custom output converters to register with the builder
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withOutputConverters(OutputConverter<?,?>... outputConverters) {
        this.outputConverterProviders.add(fixedConfig(outputConverters));
        return this;
    }

    public GraphQLSchemaGenerator withOutputConverters(ExtensionProvider<OutputConverter> provider) {
        this.outputConverterProviders.add(provider);
        return this;
    }

    /**
     * Type adapters (instances of {@link AbstractTypeAdapter}) are both type mappers and bi-directional converters,
     * implementing {@link TypeMapper}, {@link InputConverter} and {@link OutputConverter}.
     * They're used in the same way as mappers/converters individually, and exist solely because it can sometimes
     * be convenient to group the logic for mapping and converting to/from the same Java type in one place.
     * For example, because GraphQL type system has no notion of maps, {@link java.util.Map}s require special logic
     * both when mapping them to a GraphQL type and when converting them before and after invoking a Java method.
     * For this reason, all code dealing with translating {@link java.util.Map}s is kept in one place in
     * {@link io.leangen.graphql.generator.mapping.common.MapToListTypeAdapter}.
     * <p><b>Ordering of mappers/converters is strictly important as the first one supporting the given Java type
     * will be used to map/convert it.</b></p>
     * <p>See {@link #withTypeMappers(ExtensionProvider)}</p>
     * <p>See {@link #withInputConverters(ExtensionProvider)}</p>
     * <p>See {@link #withOutputConverters(ExtensionProvider)}</p>
     *
     * @param typeAdapters Custom type adapters to register with the builder
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withTypeAdapters(AbstractTypeAdapter<?,?>... typeAdapters) {
        withInputConverters((conf, defaults) -> defaults.insert(0, typeAdapters));
        withOutputConverters((conf, defaults) -> defaults.insert(0, typeAdapters));
        return withTypeMappers((conf, defaults) -> defaults.insertAfter(ScalarMapper.class, typeAdapters));
    }

    public GraphQLSchemaGenerator withArgumentInjectors(ArgumentInjector... argumentInjectors) {
        this.argumentInjectorProviders.add(fixedConfig(argumentInjectors));
        return this;
    }

    public GraphQLSchemaGenerator withArgumentInjectors(ExtensionProvider<ArgumentInjector> provider) {
        this.argumentInjectorProviders.add(provider);
        return this;
    }

    public GraphQLSchemaGenerator withAdditionalTypes(Collection<GraphQLType> additionalTypes) {
        additionalTypes.stream()
                .filter(type -> !isInternalType(type))
                .forEach(this.additionalTypes::add);
        return this;
    }

    public GraphQLSchemaGenerator withOperationBuilder(OperationBuilder operationBuilder) {
        this.operationBuilder = operationBuilder;
        return this;
    }

    /**
     * Sets a flag that all mutations should be mapped in a Relay-compliant way,
     * using the default name and description for output wrapper fields.
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withRelayCompliantMutations() {
        return withRelayCompliantMutations("result", "Mutation result");
    }

    /**
     * Sets a flag that all mutations should be mapped in a Relay-compliant way,
     * using the default name and description for output wrapper fields.
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withRelayCompliantMutations(String wrapperFieldName, String wrapperFieldDescription) {
        this.relayMappingConfig.relayCompliantMutations = true;
        this.relayMappingConfig.wrapperFieldName = wrapperFieldName;
        this.relayMappingConfig.wrapperFieldDescription = wrapperFieldDescription;
        return this;
    }

    /**
     * Sets the flag controlling whether the Node interface (as defined by the Relay spec) should be automatically
     * inferred for types that have an ID field.
     *
     * @param enabled Whether the inference should be enabled
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withRelayNodeInterfaceInference(boolean enabled) {
        this.relayMappingConfig.inferNodeInterface = enabled;
        return this;
    }

    /**
     * Registers custom schema processors that can perform arbitrary transformations on the schema just before it is built.
     *
     * @param processors Custom processors to call right before the GraphQL schema is built
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     */
    public GraphQLSchemaGenerator withSchemaProcessors(GraphQLSchemaProcessor... processors) {
        addAll(this.processors, processors);
        return this;
    }

    /**
     * Registers all built-in {@link TypeMapper}s, {@link InputConverter}s and {@link OutputConverter}s
     * <p>Equivalent to calling {@code withDefaultResolverBuilders().withDefaultMappers().withDefaultConverters()}</p>
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     *
     * @deprecated No longer needed, as {@code withXYZ(ExtensionProvider)} methods provide a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaults() {
        return withDefaultResolverBuilders()
                .withDefaultNestedResolverBuilders()
                .withDefaultMappers()
                .withDefaultConverters()
                .withDefaultArgumentInjectors();
    }

    /**
     * Registers all built-in {@link TypeMapper}s
     * <p>See {@link #withTypeMappers(TypeMapper...)}</p>
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     *
     * @deprecated No longer needed, as {@link #withTypeMappers(ExtensionProvider)} provides a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultMappers() {
        return withTypeMappers(defaultConfig());
    }

    /**
     * @deprecated No longer needed, as {@link #withInputConverters(ExtensionProvider)} provides a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultInputConverters() {
        return withInputConverters(defaultConfig());
    }

    /**
     * @deprecated No longer needed, as {@link #withOutputConverters(ExtensionProvider)} provides a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultOutputConverters() {
        return withOutputConverters(defaultConfig());
    }

    /**
     * Registers all built-in {@link InputConverter}s and {@link OutputConverter}s.
     * The equivalent of calling both {@link #withDefaultInputConverters()} and {@link #withDefaultOutputConverters()}.
     * <p>See {@link #withInputConverters(InputConverter[])} and {@link #withOutputConverters(OutputConverter[])} )}</p>
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     *
     * @deprecated No longer needed, as {@link #withInputConverters(ExtensionProvider)} and
     * {@link #withOutputConverters(ExtensionProvider)} provide a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultConverters() {
        return withDefaultInputConverters().withDefaultOutputConverters();
    }

    /**
     * @deprecated No longer needed, as {@link #withArgumentInjectors(ExtensionProvider)} provides a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultArgumentInjectors() {
        return withArgumentInjectors(defaultConfig());
    }

    /**
     * Registers default resolver builders. Currently this only includes {@link AnnotatedResolverBuilder}.
     * <p>See {@link #withResolverBuilders(ResolverBuilder...)}</p>
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     *
     * @deprecated No longer needed, as {@link #withResolverBuilders(ExtensionProvider)} provides a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultResolverBuilders() {
        return withResolverBuilders(defaultConfig());
    }

    /**
     * Registers default resolver builders. Currently this only includes {@link AnnotatedResolverBuilder}.
     * <p>See {@link #withResolverBuilders(ResolverBuilder...)}</p>
     *
     * @return This {@link GraphQLSchemaGenerator} instance, to allow method chaining
     *
     * @deprecated No longer needed, as {@link #withNestedResolverBuilders(ExtensionProvider)} provides a better alternative
     */
    @Deprecated
    public GraphQLSchemaGenerator withDefaultNestedResolverBuilders() {
        return withNestedResolverBuilders(defaultConfig());
    }

    /**
     * Sets the default values for all settings not configured explicitly,
     * ensuring the builder is in a valid state
     */
    private void init() {
        Configuration configuration = new Configuration(interfaceStrategy, scalarStrategy, typeTransformer, basePackages);
        if (operationSourceRepository.isEmpty()) {
            throw new IllegalStateException("At least one top-level operation source must be registered");
        }

        if (inclusionStrategy == null) {
            inclusionStrategy = new DefaultInclusionStrategy(basePackages);
        }
        ValueMapperFactory<?> internalValueMapperFactory = valueMapperFactory != null
                ? valueMapperFactory
                : Defaults.valueMapperFactory(typeInfoGenerator);
        if (scalarStrategy == null) {
            if (internalValueMapperFactory instanceof ScalarDeserializationStrategy) {
                scalarStrategy = (ScalarDeserializationStrategy) internalValueMapperFactory;
            } else {
                scalarStrategy = (ScalarDeserializationStrategy) Defaults.valueMapperFactory(typeInfoGenerator);
            }
        }

        if (resolverBuilderProviders.isEmpty()) {
            resolverBuilderProviders.add(defaultConfig());
        }
        List<ResolverBuilder> defaultResolverBuilders = Collections.singletonList(new AnnotatedResolverBuilder());
        Set<ResolverBuilder> collectedResolverBuilders = new LinkedHashSet<>();
        resolverBuilderProviders.forEach(config -> collectedResolverBuilders.addAll(config.getExtensions(configuration, new ExtensionList<>(defaultResolverBuilders))));
        if (collectedResolverBuilders.isEmpty()) {
            throw new IllegalStateException("Configuration error: No resolver builders registered");
        }
        operationSourceRepository.registerGlobalResolverBuilders(collectedResolverBuilders);

        if (nestedResolverBuilderProviders.isEmpty()) {
            nestedResolverBuilderProviders.add(defaultConfig());
        }
        List<ResolverBuilder> defaultNestedResolverBuilders = Arrays.asList(
                new AnnotatedResolverBuilder(),
                new BeanResolverBuilder(basePackages).withJavaDeprecationRespected(respectJavaDeprecation));
        Set<ResolverBuilder> collectedNestedResolverBuilders = new LinkedHashSet<>();
        nestedResolverBuilderProviders.forEach(config -> collectedNestedResolverBuilders.addAll(config.getExtensions(configuration, new ExtensionList<>(defaultNestedResolverBuilders))));
        if (collectedNestedResolverBuilders.isEmpty()) {
            throw new IllegalStateException("Configuration error: No nested resolver builders registered");
        }
        operationSourceRepository.registerGlobalNestedResolverBuilders(collectedNestedResolverBuilders);

        if (typeMapperProviders.isEmpty()) {
            typeMapperProviders.add(defaultConfig());
        }
        ObjectTypeMapper objectTypeMapper = new ObjectTypeMapper();
        EnumMapper enumMapper = new EnumMapper(respectJavaDeprecation);
        List<TypeMapper> defaultMappers = Arrays.asList(
                new NonNullMapper(), new IdAdapter(), new ScalarMapper(), new CompletableFutureMapper(),
                new PublisherMapper(), new OptionalIntAdapter(), new OptionalLongAdapter(), new OptionalDoubleAdapter(),
                new ByteArrayToBase64Adapter(), enumMapper, new ArrayAdapter(), new UnionTypeMapper(),
                new UnionInlineMapper(), new StreamToCollectionTypeAdapter(), new DataFetcherResultMapper(),
                new VoidToBooleanTypeAdapter(), new ListMapper(), new PageMapper(), new OptionalAdapter(), new EnumMapToObjectTypeAdapter(enumMapper),
                new ObjectScalarAdapter(scalarStrategy), new InterfaceMapper(interfaceStrategy, objectTypeMapper), objectTypeMapper);
        typeMappers = typeMapperProviders.stream()
                .flatMap(provider -> provider.getExtensions(configuration, new ExtensionList<>(defaultMappers)).stream())
                .distinct()
                .collect(Collectors.toList());
        if (typeMappers.isEmpty()) {
            throw new IllegalStateException("Configuration error: No type mappers registered");
        }

        if (outputConverterProviders.isEmpty()) {
            outputConverterProviders.add(defaultConfig());
        }
        List<OutputConverter> defaultOutputConverters = Arrays.asList(
                new IdAdapter(), new VoidToBooleanTypeAdapter(), new ByteArrayToBase64Adapter(),
                new ArrayAdapter(), new CollectionOutputConverter(), new OptionalIntAdapter(),
                new OptionalLongAdapter(), new OptionalDoubleAdapter(), new OptionalAdapter(),
                new StreamToCollectionTypeAdapter(), new ObjectScalarAdapter(scalarStrategy));
        List<OutputConverter> outputConverters = outputConverterProviders.stream()
                .flatMap(provider -> provider.getExtensions(configuration, new ExtensionList<>(defaultOutputConverters)).stream())
                .distinct()
                .collect(Collectors.toList());

        if (inputConverterProviders.isEmpty()) {
            inputConverterProviders.add(defaultConfig());
        }
        List<InputConverter> defaultInputConverters = Arrays.asList(
                new OptionalIntAdapter(), new OptionalLongAdapter(), new OptionalDoubleAdapter(),
                new OptionalAdapter(), new StreamToCollectionTypeAdapter(), new ByteArrayToBase64Adapter(), new EnumMapToObjectTypeAdapter(enumMapper));
        List<InputConverter> inputConverters = inputConverterProviders.stream()
                .flatMap(provider -> provider.getExtensions(configuration, new ExtensionList<>(defaultInputConverters)).stream())
                .distinct()
                .collect(Collectors.toList());

        if (argumentInjectorProviders.isEmpty()) {
            argumentInjectorProviders.add(defaultConfig());
        }
        List<ArgumentInjector> defaultArgumentInjectors = Arrays.asList(
                new IdAdapter(), new RootContextInjector(), new ContextInjector(),
                new EnvironmentInjector(), new InputValueDeserializer());
        List<ArgumentInjector> argumentInjectors = argumentInjectorProviders.stream()
                .flatMap(provider -> provider.getExtensions(configuration, new ExtensionList<>(defaultArgumentInjectors)).stream())
                .distinct()
                .collect(Collectors.toList());
        if (argumentInjectors.isEmpty()) {
            throw new IllegalStateException("Configuration error: No argument injector registered");
        }

        environment = new GlobalEnvironment(new Relay(), new TypeRepository(additionalTypes), new ConverterRepository(inputConverters, outputConverters), new ArgumentInjectorRepository(argumentInjectors));
        valueMapperFactory = new MemoizedValueMapperFactory<>(environment, internalValueMapperFactory);
        if (inputFieldStrategy == null) {
            ValueMapper def = valueMapperFactory.getValueMapper();
            if (def instanceof InputFieldDiscoveryStrategy) {
                inputFieldStrategy = (InputFieldDiscoveryStrategy) def;
            } else {
                inputFieldStrategy = (InputFieldDiscoveryStrategy) Defaults.valueMapperFactory(typeInfoGenerator).getValueMapper();
            }
        }
    }

    /**
     * Generates a GraphQL schema based on the results of analysis of the registered sources. All exposed methods will be mapped
     * as queries or mutations and all Java types referred to by those methods will be mapped to corresponding GraphQL types.
     * Such schema can then be used to construct {@link graphql.GraphQL} instances. See the example in the description of this class.
     *
     * @return A GraphQL schema
     */
    public GraphQLSchema generate() {
        init();

        BuildContext buildContext = new BuildContext(
                basePackages, environment, new OperationRepository(operationSourceRepository, operationBuilder, inclusionStrategy, typeTransformer, basePackages),
                new TypeMapperRepository(typeMappers), valueMapperFactory, typeInfoGenerator, interfaceStrategy, scalarStrategy, typeTransformer,
                abstractInputHandler, inputFieldStrategy, inclusionStrategy, relayMappingConfig, additionalTypes, implDiscoveryStrategy);
        OperationMapper operationMapper = new OperationMapper(buildContext);

        GraphQLSchema.Builder builder = GraphQLSchema.newSchema()
                .query(newObject()
                        .name(queryRoot)
                        .description("Query root type")
                        .fields(operationMapper.getQueries())
                        .build());

        List<GraphQLFieldDefinition> mutations = operationMapper.getMutations();
        if (!mutations.isEmpty()) {
            builder.mutation(newObject()
                    .name(mutationRoot)
                    .description("Mutation root type")
                    .fields(mutations)
                    .build());
        }

        List<GraphQLFieldDefinition> subscriptions = operationMapper.getSubscriptions();
        if (!subscriptions.isEmpty()) {
            builder.subscription(newObject()
                    .name(subscriptionRoot)
                    .description("Subscription root type")
                    .fields(subscriptions)
                    .build());
        }
        applyProcessors(builder, buildContext);

        additionalTypes.addAll(buildContext.typeRepository.getDiscoveredTypes());

        return builder.build(additionalTypes);
    }

    private void applyProcessors(GraphQLSchema.Builder builder, BuildContext buildContext) {
        for (GraphQLSchemaProcessor processor : processors) {
            processor.process(builder, buildContext);
        }
    }

    private boolean isInternalType(GraphQLType type) {
        return GraphQLUtils.isIntrospectionType(type) ||
                type.getName().equals(queryRoot) ||
                type.getName().equals(mutationRoot) ||
                type.getName().equals(subscriptionRoot);
    }

    private void checkType(Type type) {
        if (type == null) {
            throw new TypeMappingException();
        }
        Class<?> clazz = ClassUtils.getRawType(type);
        if (ClassUtils.isProxy(clazz)) {
            throw new TypeMappingException("The registered object of type " + clazz.getName() +
                    " appears to be a dynamically generated proxy, so its type can not be reliably determined." +
                    " Provide the type explicitly when registering the bean." +
                    " For details and solutions see " + Urls.Errors.DYNAMIC_PROXIES);
        }
        if (ClassUtils.isMissingTypeParameters(type)) {
            throw new TypeMappingException("The registered object is of generic type " + type.getTypeName() + "." +
                    " Provide the full type explicitly when registering the bean." +
                    " For details and solutions see " + Urls.Errors.TOP_LEVEL_GENERICS);
        }
    }

    private void checkType(AnnotatedType type) {
        if (type == null) {
            throw new TypeMappingException();
        }
        checkType(type.getType());
    }

    private <T> ExtensionProvider<T> defaultConfig() {
        return (config, defaults) -> defaults;
    }

    private <T> ExtensionProvider<T> fixedConfig(T[] additions) {
        return (config, defaults) -> Arrays.asList(additions);
    }

    public static class Configuration {
        public final InterfaceMappingStrategy interfaceMappingStrategy;
        public final ScalarDeserializationStrategy scalarDeserializationStrategy;
        public final TypeTransformer typeTransformer;
        public final String[] basePackages;

        public Configuration(InterfaceMappingStrategy interfaceMappingStrategy, ScalarDeserializationStrategy scalarDeserializationStrategy, TypeTransformer typeTransformer, String[] basePackages) {
            this.interfaceMappingStrategy = interfaceMappingStrategy;
            this.scalarDeserializationStrategy = scalarDeserializationStrategy;
            this.typeTransformer = typeTransformer;
            this.basePackages = basePackages;
        }
    }

    public static final class ExtensionList<E> extends ArrayList<E> {

        ExtensionList(Collection<? extends E> c) {
            super(c);
        }

        @SafeVarargs
        public final ExtensionList<E> append(E... extensions) {
            Collections.addAll(this, extensions);
            return this;
        }

        public ExtensionList<E> append(Collection<E> extensions) {
            super.addAll(extensions);
            return this;
        }

        @SafeVarargs
        public final ExtensionList<E> insert(int index, E... extensions) {
            for (int i = 0; i < extensions.length; i++) {
                add(index + i, extensions[i]);
            }
            return this;
        }

        @SafeVarargs
        public final ExtensionList<E> insertAfter(Class<? extends E> extensionType, E... extensions) {
            return insert(firstIndexOfType(extensionType) + 1, extensions);
        }

        @SafeVarargs
        public final ExtensionList<E> insertBefore(Class<? extends E> extensionType, E... extensions) {
            return insert(firstIndexOfType(extensionType), extensions);
        }

        public ExtensionList<E> drop(int index) {
            super.remove(index);
            return this;
        }

        public ExtensionList<E> drop(Class<? extends E> extensionType) {
            return drop(firstIndexOfType(extensionType));
        }

        public ExtensionList<E> dropAll(Predicate<? super E> filter) {
            super.removeIf(filter);
            return this;
        }

        public ExtensionList<E> replace(int index, E replacement) {
            super.set(index, replacement);
            return this;
        }

        public ExtensionList<E> replace(Class<? extends E> extensionType, E replacement) {
            return replace(firstIndexOfType(extensionType), replacement);
        }

        private int firstIndexOfType(Class<? extends E> extensionType) {
            for (int i = 0; i < size(); i++) {
                if (extensionType.isInstance(get(i))) {
                    return i;
                }
            }
            throw new IllegalArgumentException("Extension of type " + extensionType.getName() + " not found");
        }
    }

    //TODO Remove MemoizedValueMapperFactory
    private static class MemoizedValueMapperFactory<T extends ValueMapper> implements ValueMapperFactory<T> {

        private final T defaultValueMapper;
        private final ValueMapperFactory<T> delegate;

        public MemoizedValueMapperFactory(GlobalEnvironment environment, ValueMapperFactory<T> delegate) {
            this.defaultValueMapper = delegate.getValueMapper(Collections.emptyMap(), environment);
            this.delegate = delegate;
        }

        @Override
        public T getValueMapper(Map<Class, List<Class>> concreteSubTypes, GlobalEnvironment environment) {
            if (concreteSubTypes.isEmpty() || concreteSubTypes.values().stream().allMatch(List::isEmpty)) {
                return this.defaultValueMapper;
            }
            return delegate.getValueMapper(concreteSubTypes, environment);
        }
    }
}
