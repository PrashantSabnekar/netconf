/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.sal.restconf.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Closeable;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.core.Response.Status;
import org.apache.aries.blueprint.annotation.service.Reference;
import org.opendaylight.mdsal.dom.api.DOMMountPoint;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.mdsal.dom.api.DOMYangTextSourceProvider;
import org.opendaylight.netconf.sal.rest.api.Draft02;
import org.opendaylight.netconf.sal.rest.api.Draft02.RestConfModule;
import org.opendaylight.restconf.common.context.InstanceIdentifierContext;
import org.opendaylight.restconf.common.errors.RestconfDocumentedException;
import org.opendaylight.restconf.common.errors.RestconfError.ErrorTag;
import org.opendaylight.restconf.common.errors.RestconfError.ErrorType;
import org.opendaylight.restconf.common.util.RestUtil;
import org.opendaylight.yangtools.concepts.IllegalArgumentCodec;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.Revision;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.AugmentationIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.InstanceIdentifierBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifierWithPredicates;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.model.api.AnyxmlSchemaNode;
import org.opendaylight.yangtools.yang.model.api.CaseSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContextListener;
import org.opendaylight.yangtools.yang.model.api.GroupingDefinition;
import org.opendaylight.yangtools.yang.model.api.LeafListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.LeafSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.TypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.IdentityrefTypeDefinition;
import org.opendaylight.yangtools.yang.model.api.type.LeafrefTypeDefinition;
import org.opendaylight.yangtools.yang.model.util.SchemaContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class ControllerContext implements EffectiveModelContextListener, Closeable {
    // FIXME: this should be in md-sal somewhere
    public static final String MOUNT = "yang-ext:mount";

    private static final Logger LOG = LoggerFactory.getLogger(ControllerContext.class);

    private static final String NULL_VALUE = "null";

    private static final String MOUNT_MODULE = "yang-ext";

    private static final String MOUNT_NODE = "mount";

    private static final Splitter SLASH_SPLITTER = Splitter.on('/');

    private final AtomicReference<Map<QName, RpcDefinition>> qnameToRpc = new AtomicReference<>(Collections.emptyMap());

    private final DOMMountPointService mountService;
    private final DOMYangTextSourceProvider yangTextSourceProvider;
    private final ListenerRegistration<?> listenerRegistration;
    private volatile EffectiveModelContext globalSchema;
    private volatile DataNormalizer dataNormalizer;

    @Inject
    public ControllerContext(final @Reference DOMSchemaService schemaService,
            final @Reference DOMMountPointService mountService, final @Reference DOMSchemaService domSchemaService) {
        this.mountService = mountService;
        this.yangTextSourceProvider = domSchemaService.getExtensions().getInstance(DOMYangTextSourceProvider.class);

        onModelContextUpdated(schemaService.getGlobalContext());
        listenerRegistration = schemaService.registerSchemaContextListener(this);
    }

    /**
     * Factory method.
     *
     * @deprecated Just use the
     *             {@link #ControllerContext(DOMSchemaService, DOMMountPointService, DOMSchemaService)}
     *             constructor instead.
     */
    @Deprecated
    public static ControllerContext newInstance(final DOMSchemaService schemaService,
            final DOMMountPointService mountService, final DOMSchemaService domSchemaService) {
        return new ControllerContext(schemaService, mountService, domSchemaService);
    }

    private void setGlobalSchema(final EffectiveModelContext globalSchema) {
        this.globalSchema = globalSchema;
        this.dataNormalizer = new DataNormalizer(globalSchema);
    }

    public DOMYangTextSourceProvider getYangTextSourceProvider() {
        return yangTextSourceProvider;
    }

    private void checkPreconditions() {
        if (this.globalSchema == null) {
            throw new RestconfDocumentedException(Status.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        listenerRegistration.close();
    }

    public void setSchemas(final EffectiveModelContext schemas) {
        onModelContextUpdated(schemas);
    }

    public InstanceIdentifierContext<?> toInstanceIdentifier(final String restconfInstance) {
        return toIdentifier(restconfInstance, false);
    }

    public EffectiveModelContext getGlobalSchema() {
        return this.globalSchema;
    }

    public InstanceIdentifierContext<?> toMountPointIdentifier(final String restconfInstance) {
        return toIdentifier(restconfInstance, true);
    }

    private InstanceIdentifierContext<?> toIdentifier(final String restconfInstance,
                                                      final boolean toMountPointIdentifier) {
        checkPreconditions();

        if (restconfInstance == null) {
            return new InstanceIdentifierContext<>(YangInstanceIdentifier.empty(), this.globalSchema, null,
                    this.globalSchema);
        }

        final List<String> pathArgs = urlPathArgsDecode(SLASH_SPLITTER.split(restconfInstance));
        omitFirstAndLastEmptyString(pathArgs);
        if (pathArgs.isEmpty()) {
            return null;
        }

        final String first = pathArgs.iterator().next();
        final String startModule = toModuleName(first);
        if (startModule == null) {
            throw new RestconfDocumentedException("First node in URI has to be in format \"moduleName:nodeName\"",
                    ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
        }

        final InstanceIdentifierBuilder builder = YangInstanceIdentifier.builder();
        final Collection<? extends Module> latestModule = this.globalSchema.findModules(startModule);

        if (latestModule.isEmpty()) {
            throw new RestconfDocumentedException("The module named '" + startModule + "' does not exist.",
                    ErrorType.PROTOCOL, ErrorTag.UNKNOWN_ELEMENT);
        }

        final InstanceIdentifierContext<?> iiWithSchemaNode =
                collectPathArguments(builder, pathArgs, latestModule.iterator().next(), null, toMountPointIdentifier);

        if (iiWithSchemaNode == null) {
            throw new RestconfDocumentedException("URI has bad format", ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
        }

        return iiWithSchemaNode;
    }

    private static List<String> omitFirstAndLastEmptyString(final List<String> list) {
        if (list.isEmpty()) {
            return list;
        }

        final String head = list.iterator().next();
        if (head.isEmpty()) {
            list.remove(0);
        }

        if (list.isEmpty()) {
            return list;
        }

        final String last = list.get(list.size() - 1);
        if (last.isEmpty()) {
            list.remove(list.size() - 1);
        }

        return list;
    }

    public Module findModuleByName(final String moduleName) {
        checkPreconditions();
        checkArgument(moduleName != null && !moduleName.isEmpty());
        return this.globalSchema.findModules(moduleName).stream().findFirst().orElse(null);
    }

    public Module findModuleByName(final DOMMountPoint mountPoint, final String moduleName) {
        checkArgument(moduleName != null && mountPoint != null);

        final SchemaContext mountPointSchema = getModelContext(mountPoint);
        if (mountPointSchema == null) {
            return null;
        }

        return mountPointSchema.findModules(moduleName).stream().findFirst().orElse(null);
    }

    public Module findModuleByNamespace(final URI namespace) {
        checkPreconditions();
        checkArgument(namespace != null);
        return this.globalSchema.findModules(namespace).stream().findFirst().orElse(null);
    }

    public Module findModuleByNamespace(final DOMMountPoint mountPoint, final URI namespace) {
        checkArgument(namespace != null && mountPoint != null);

        final SchemaContext mountPointSchema = getModelContext(mountPoint);
        if (mountPointSchema == null) {
            return null;
        }

        return mountPointSchema.findModules(namespace).stream().findFirst().orElse(null);
    }

    public Module findModuleByNameAndRevision(final String name, final Revision revision) {
        checkPreconditions();
        checkArgument(name != null && revision != null);

        return this.globalSchema.findModule(name, revision).orElse(null);
    }

    public Module findModuleByNameAndRevision(final DOMMountPoint mountPoint, final String name,
            final Revision revision) {
        checkPreconditions();
        checkArgument(name != null && revision != null && mountPoint != null);

        final SchemaContext schemaContext = getModelContext(mountPoint);
        return schemaContext == null ? null : schemaContext.findModule(name, revision).orElse(null);
    }

    public DataNodeContainer getDataNodeContainerFor(final YangInstanceIdentifier path) {
        checkPreconditions();

        final Iterable<PathArgument> elements = path.getPathArguments();
        final PathArgument head = elements.iterator().next();
        final QName startQName = head.getNodeType();
        final Module initialModule = this.globalSchema.findModule(startQName.getModule()).orElse(null);
        DataNodeContainer node = initialModule;
        for (final PathArgument element : elements) {
            final QName _nodeType = element.getNodeType();
            final DataSchemaNode potentialNode = childByQName(node, _nodeType);
            if (potentialNode == null || !isListOrContainer(potentialNode)) {
                return null;
            }
            node = (DataNodeContainer) potentialNode;
        }

        return node;
    }

    public String toFullRestconfIdentifier(final YangInstanceIdentifier path, final DOMMountPoint mount) {
        checkPreconditions();

        final Iterable<PathArgument> elements = path.getPathArguments();
        final StringBuilder builder = new StringBuilder();
        final PathArgument head = elements.iterator().next();
        final QName startQName = head.getNodeType();
        final SchemaContext schemaContext;
        if (mount != null) {
            schemaContext = getModelContext(mount);
        } else {
            schemaContext = this.globalSchema;
        }
        final Module initialModule = schemaContext.findModule(startQName.getModule()).orElse(null);
        DataNodeContainer node = initialModule;
        for (final PathArgument element : elements) {
            if (!(element instanceof AugmentationIdentifier)) {
                final QName _nodeType = element.getNodeType();
                final DataSchemaNode potentialNode = childByQName(node, _nodeType);
                if ((!(element instanceof NodeIdentifier) || !(potentialNode instanceof ListSchemaNode))
                        && !(potentialNode instanceof ChoiceSchemaNode)) {
                    builder.append(convertToRestconfIdentifier(element, potentialNode, mount));
                    if (potentialNode instanceof DataNodeContainer) {
                        node = (DataNodeContainer) potentialNode;
                    }
                }
            }
        }

        return builder.toString();
    }

    public String findModuleNameByNamespace(final URI namespace) {
        checkPreconditions();

        final Module module = this.findModuleByNamespace(namespace);
        return module == null ? null : module.getName();
    }

    public String findModuleNameByNamespace(final DOMMountPoint mountPoint, final URI namespace) {
        final Module module = this.findModuleByNamespace(mountPoint, namespace);
        return module == null ? null : module.getName();
    }

    public URI findNamespaceByModuleName(final String moduleName) {
        final Module module = this.findModuleByName(moduleName);
        return module == null ? null : module.getNamespace();
    }

    public URI findNamespaceByModuleName(final DOMMountPoint mountPoint, final String moduleName) {
        final Module module = this.findModuleByName(mountPoint, moduleName);
        return module == null ? null : module.getNamespace();
    }

    public Collection<? extends Module> getAllModules(final DOMMountPoint mountPoint) {
        checkPreconditions();

        final SchemaContext schemaContext = mountPoint == null ? null : getModelContext(mountPoint);
        return schemaContext == null ? null : schemaContext.getModules();
    }

    public Collection<? extends Module> getAllModules() {
        checkPreconditions();
        return this.globalSchema.getModules();
    }

    private static CharSequence toRestconfIdentifier(final SchemaContext context, final QName qname) {
        final Module schema = context.findModule(qname.getModule()).orElse(null);
        return schema == null ? null : schema.getName() + ':' + qname.getLocalName();
    }

    public CharSequence toRestconfIdentifier(final QName qname, final DOMMountPoint mount) {
        final SchemaContext schema;
        if (mount != null) {
            schema = getModelContext(mount);
        } else {
            checkPreconditions();
            schema = this.globalSchema;
        }

        return toRestconfIdentifier(schema, qname);
    }

    public CharSequence toRestconfIdentifier(final QName qname) {
        checkPreconditions();

        return toRestconfIdentifier(this.globalSchema, qname);
    }

    public CharSequence toRestconfIdentifier(final DOMMountPoint mountPoint, final QName qname) {
        if (mountPoint == null) {
            return null;
        }

        return toRestconfIdentifier(getModelContext(mountPoint), qname);
    }

    public Module getRestconfModule() {
        return findModuleByNameAndRevision(Draft02.RestConfModule.NAME, Revision.of(Draft02.RestConfModule.REVISION));
    }

    public DataSchemaNode getRestconfModuleErrorsSchemaNode() {
        final Module restconfModule = getRestconfModule();
        if (restconfModule == null) {
            return null;
        }

        final Collection<? extends GroupingDefinition> groupings = restconfModule.getGroupings();

        final Iterable<? extends GroupingDefinition> filteredGroups = Iterables.filter(groupings,
            g -> RestConfModule.ERRORS_GROUPING_SCHEMA_NODE.equals(g.getQName().getLocalName()));

        final GroupingDefinition restconfGrouping = Iterables.getFirst(filteredGroups, null);

        final List<DataSchemaNode> instanceDataChildrenByName = findInstanceDataChildrenByName(restconfGrouping,
                RestConfModule.ERRORS_CONTAINER_SCHEMA_NODE);
        return Iterables.getFirst(instanceDataChildrenByName, null);
    }

    public DataSchemaNode getRestconfModuleRestConfSchemaNode(final Module inRestconfModule,
            final String schemaNodeName) {
        Module restconfModule = inRestconfModule;
        if (restconfModule == null) {
            restconfModule = getRestconfModule();
        }

        if (restconfModule == null) {
            return null;
        }

        final Collection<? extends GroupingDefinition> groupings = restconfModule.getGroupings();
        final Iterable<? extends GroupingDefinition> filteredGroups = Iterables.filter(groupings,
            g -> RestConfModule.RESTCONF_GROUPING_SCHEMA_NODE.equals(g.getQName().getLocalName()));
        final GroupingDefinition restconfGrouping = Iterables.getFirst(filteredGroups, null);

        final List<DataSchemaNode> instanceDataChildrenByName = findInstanceDataChildrenByName(restconfGrouping,
                RestConfModule.RESTCONF_CONTAINER_SCHEMA_NODE);
        final DataSchemaNode restconfContainer = Iterables.getFirst(instanceDataChildrenByName, null);

        if (RestConfModule.OPERATIONS_CONTAINER_SCHEMA_NODE.equals(schemaNodeName)) {
            final List<DataSchemaNode> instances = findInstanceDataChildrenByName(
                    (DataNodeContainer) restconfContainer, RestConfModule.OPERATIONS_CONTAINER_SCHEMA_NODE);
            return Iterables.getFirst(instances, null);
        } else if (RestConfModule.STREAMS_CONTAINER_SCHEMA_NODE.equals(schemaNodeName)) {
            final List<DataSchemaNode> instances = findInstanceDataChildrenByName(
                    (DataNodeContainer) restconfContainer, RestConfModule.STREAMS_CONTAINER_SCHEMA_NODE);
            return Iterables.getFirst(instances, null);
        } else if (RestConfModule.STREAM_LIST_SCHEMA_NODE.equals(schemaNodeName)) {
            List<DataSchemaNode> instances = findInstanceDataChildrenByName(
                    (DataNodeContainer) restconfContainer, RestConfModule.STREAMS_CONTAINER_SCHEMA_NODE);
            final DataSchemaNode modules = Iterables.getFirst(instances, null);
            instances = findInstanceDataChildrenByName((DataNodeContainer) modules,
                    RestConfModule.STREAM_LIST_SCHEMA_NODE);
            return Iterables.getFirst(instances, null);
        } else if (RestConfModule.MODULES_CONTAINER_SCHEMA_NODE.equals(schemaNodeName)) {
            final List<DataSchemaNode> instances = findInstanceDataChildrenByName(
                    (DataNodeContainer) restconfContainer, RestConfModule.MODULES_CONTAINER_SCHEMA_NODE);
            return Iterables.getFirst(instances, null);
        } else if (RestConfModule.MODULE_LIST_SCHEMA_NODE.equals(schemaNodeName)) {
            List<DataSchemaNode> instances = findInstanceDataChildrenByName(
                    (DataNodeContainer) restconfContainer, RestConfModule.MODULES_CONTAINER_SCHEMA_NODE);
            final DataSchemaNode modules = Iterables.getFirst(instances, null);
            instances = findInstanceDataChildrenByName((DataNodeContainer) modules,
                    RestConfModule.MODULE_LIST_SCHEMA_NODE);
            return Iterables.getFirst(instances, null);
        } else if (RestConfModule.STREAMS_CONTAINER_SCHEMA_NODE.equals(schemaNodeName)) {
            final List<DataSchemaNode> instances = findInstanceDataChildrenByName(
                    (DataNodeContainer) restconfContainer, RestConfModule.STREAMS_CONTAINER_SCHEMA_NODE);
            return Iterables.getFirst(instances, null);
        }

        return null;
    }

    private static DataSchemaNode childByQName(final ChoiceSchemaNode container, final QName name) {
        for (final CaseSchemaNode caze : container.getCases()) {
            final DataSchemaNode ret = childByQName(caze, name);
            if (ret != null) {
                return ret;
            }
        }

        return null;
    }

    private static DataSchemaNode childByQName(final CaseSchemaNode container, final QName name) {
        return container.getDataChildByName(name);
    }

    private static DataSchemaNode childByQName(final ContainerSchemaNode container, final QName name) {
        return dataNodeChildByQName(container, name);
    }

    private static DataSchemaNode childByQName(final ListSchemaNode container, final QName name) {
        return dataNodeChildByQName(container, name);
    }

    private static DataSchemaNode childByQName(final Module container, final QName name) {
        return dataNodeChildByQName(container, name);
    }

    private static DataSchemaNode childByQName(final DataSchemaNode container, final QName name) {
        return null;
    }


    private static DataSchemaNode childByQName(final Object container, final QName name) {
        if (container instanceof CaseSchemaNode) {
            return childByQName((CaseSchemaNode) container, name);
        } else if (container instanceof ChoiceSchemaNode) {
            return childByQName((ChoiceSchemaNode) container, name);
        } else if (container instanceof ContainerSchemaNode) {
            return childByQName((ContainerSchemaNode) container, name);
        } else if (container instanceof ListSchemaNode) {
            return childByQName((ListSchemaNode) container, name);
        } else if (container instanceof DataSchemaNode) {
            return childByQName((DataSchemaNode) container, name);
        } else if (container instanceof Module) {
            return childByQName((Module) container, name);
        } else {
            throw new IllegalArgumentException("Unhandled parameter types: "
                    + Arrays.asList(container, name).toString());
        }
    }

    private static DataSchemaNode dataNodeChildByQName(final DataNodeContainer container, final QName name) {
        final DataSchemaNode ret = container.getDataChildByName(name);
        if (ret == null) {
            for (final DataSchemaNode node : container.getChildNodes()) {
                if (node instanceof ChoiceSchemaNode) {
                    final ChoiceSchemaNode choiceNode = (ChoiceSchemaNode) node;
                    final DataSchemaNode childByQName = childByQName(choiceNode, name);
                    if (childByQName != null) {
                        return childByQName;
                    }
                }
            }
        }
        return ret;
    }

    private String toUriString(final Object object, final LeafSchemaNode leafNode, final DOMMountPoint mount)
            throws UnsupportedEncodingException {
        final IllegalArgumentCodec<Object, Object> codec = RestCodec.from(leafNode.getType(), mount, this);
        return object == null ? "" : URLEncoder.encode(codec.serialize(object).toString(), StandardCharsets.UTF_8);
    }

    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "Unrecognised NullableDecl")
    private InstanceIdentifierContext<?> collectPathArguments(final InstanceIdentifierBuilder builder,
            final List<String> strings, final DataNodeContainer parentNode, final DOMMountPoint mountPoint,
            final boolean returnJustMountPoint) {
        requireNonNull(strings);

        if (parentNode == null) {
            return null;
        }

        if (strings.isEmpty()) {
            return createContext(builder.build(), (DataSchemaNode) parentNode, mountPoint,
                mountPoint != null ? getModelContext(mountPoint) : this.globalSchema);
        }

        final String head = strings.iterator().next();

        if (head.isEmpty()) {
            final List<String> remaining = strings.subList(1, strings.size());
            return collectPathArguments(builder, remaining, parentNode, mountPoint, returnJustMountPoint);
        }

        final String nodeName = toNodeName(head);
        final String moduleName = toModuleName(head);

        DataSchemaNode targetNode = null;
        if (!Strings.isNullOrEmpty(moduleName)) {
            if (MOUNT_MODULE.equals(moduleName) && MOUNT_NODE.equals(nodeName)) {
                if (mountPoint != null) {
                    throw new RestconfDocumentedException("Restconf supports just one mount point in URI.",
                            ErrorType.APPLICATION, ErrorTag.OPERATION_NOT_SUPPORTED);
                }

                if (this.mountService == null) {
                    throw new RestconfDocumentedException(
                            "MountService was not found. Finding behind mount points does not work.",
                            ErrorType.APPLICATION, ErrorTag.OPERATION_NOT_SUPPORTED);
                }

                final YangInstanceIdentifier partialPath = this.dataNormalizer.toNormalized(builder.build());
                final Optional<DOMMountPoint> mountOpt = this.mountService.getMountPoint(partialPath);
                if (mountOpt.isEmpty()) {
                    LOG.debug("Instance identifier to missing mount point: {}", partialPath);
                    throw new RestconfDocumentedException("Mount point does not exist.", ErrorType.PROTOCOL,
                            ErrorTag.DATA_MISSING);
                }
                final DOMMountPoint mount = mountOpt.get();

                final EffectiveModelContext mountPointSchema = getModelContext(mount);
                if (mountPointSchema == null) {
                    throw new RestconfDocumentedException("Mount point does not contain any schema with modules.",
                            ErrorType.APPLICATION, ErrorTag.UNKNOWN_ELEMENT);
                }

                if (returnJustMountPoint || strings.size() == 1) {
                    final YangInstanceIdentifier instance = YangInstanceIdentifier.builder().build();
                    return new InstanceIdentifierContext<>(instance, mountPointSchema, mount, mountPointSchema);
                }

                final String moduleNameBehindMountPoint = toModuleName(strings.get(1));
                if (moduleNameBehindMountPoint == null) {
                    throw new RestconfDocumentedException(
                            "First node after mount point in URI has to be in format \"moduleName:nodeName\"",
                            ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
                }

                final Iterator<? extends Module> it = mountPointSchema.findModules(moduleNameBehindMountPoint)
                        .iterator();
                if (!it.hasNext()) {
                    throw new RestconfDocumentedException("\"" + moduleNameBehindMountPoint
                            + "\" module does not exist in mount point.", ErrorType.PROTOCOL, ErrorTag.UNKNOWN_ELEMENT);
                }

                final List<String> subList = strings.subList(1, strings.size());
                return collectPathArguments(YangInstanceIdentifier.builder(), subList, it.next(), mount,
                        returnJustMountPoint);
            }

            Module module = null;
            if (mountPoint == null) {
                checkPreconditions();
                module = this.globalSchema.findModules(moduleName).stream().findFirst().orElse(null);
                if (module == null) {
                    throw new RestconfDocumentedException("\"" + moduleName + "\" module does not exist.",
                            ErrorType.PROTOCOL, ErrorTag.UNKNOWN_ELEMENT);
                }
            } else {
                final EffectiveModelContext schemaContext = getModelContext(mountPoint);
                if (schemaContext != null) {
                    module = schemaContext.findModules(moduleName).stream().findFirst().orElse(null);
                } else {
                    module = null;
                }
                if (module == null) {
                    throw new RestconfDocumentedException("\"" + moduleName
                            + "\" module does not exist in mount point.", ErrorType.PROTOCOL, ErrorTag.UNKNOWN_ELEMENT);
                }
            }

            targetNode = findInstanceDataChildByNameAndNamespace(parentNode, nodeName, module.getNamespace());

            if (targetNode == null && parentNode instanceof Module) {
                final RpcDefinition rpc;
                if (mountPoint == null) {
                    rpc = getRpcDefinition(head, module.getRevision());
                } else {
                    final String rpcName = toNodeName(head);
                    rpc = getRpcDefinition(module, rpcName);
                }
                if (rpc != null) {
                    return new InstanceIdentifierContext<>(builder.build(), rpc, mountPoint,
                            mountPoint != null ? getModelContext(mountPoint) : this.globalSchema);
                }
            }

            if (targetNode == null) {
                throw new RestconfDocumentedException("URI has bad format. Possible reasons:\n" + " 1. \"" + head
                        + "\" was not found in parent data node.\n" + " 2. \"" + head
                        + "\" is behind mount point. Then it should be in format \"/" + MOUNT + "/" + head + "\".",
                        ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
            }
        } else {
            final List<DataSchemaNode> potentialSchemaNodes = findInstanceDataChildrenByName(parentNode, nodeName);
            if (potentialSchemaNodes.size() > 1) {
                final StringBuilder strBuilder = new StringBuilder();
                for (final DataSchemaNode potentialNodeSchema : potentialSchemaNodes) {
                    strBuilder.append("   ").append(potentialNodeSchema.getQName().getNamespace()).append("\n");
                }

                throw new RestconfDocumentedException(
                        "URI has bad format. Node \""
                                + nodeName + "\" is added as augment from more than one module. "
                                + "Therefore the node must have module name "
                                + "and it has to be in format \"moduleName:nodeName\"."
                                + "\nThe node is added as augment from modules with namespaces:\n"
                                + strBuilder.toString(), ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
            }

            if (potentialSchemaNodes.isEmpty()) {
                throw new RestconfDocumentedException("\"" + nodeName + "\" in URI was not found in parent data node",
                        ErrorType.PROTOCOL, ErrorTag.UNKNOWN_ELEMENT);
            }

            targetNode = potentialSchemaNodes.iterator().next();
        }

        if (!isListOrContainer(targetNode)) {
            throw new RestconfDocumentedException("URI has bad format. Node \"" + head
                    + "\" must be Container or List yang type.", ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
        }

        int consumed = 1;
        if (targetNode instanceof ListSchemaNode) {
            final ListSchemaNode listNode = (ListSchemaNode) targetNode;
            final int keysSize = listNode.getKeyDefinition().size();
            if (strings.size() - consumed < keysSize) {
                throw new RestconfDocumentedException("Missing key for list \"" + listNode.getQName().getLocalName()
                        + "\".", ErrorType.PROTOCOL, ErrorTag.DATA_MISSING);
            }

            final List<String> uriKeyValues = strings.subList(consumed, consumed + keysSize);
            final HashMap<QName, Object> keyValues = new HashMap<>();
            int index = 0;
            for (final QName key : listNode.getKeyDefinition()) {
                {
                    final String uriKeyValue = uriKeyValues.get(index);
                    if (uriKeyValue.equals(NULL_VALUE)) {
                        throw new RestconfDocumentedException("URI has bad format. List \""
                                + listNode.getQName().getLocalName() + "\" cannot contain \"null\" value as a key.",
                                ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
                    }

                    addKeyValue(keyValues, listNode.getDataChildByName(key), uriKeyValue, mountPoint);
                    index++;
                }
            }

            consumed = consumed + index;
            builder.nodeWithKey(targetNode.getQName(), keyValues);
        } else {
            builder.node(targetNode.getQName());
        }

        if (targetNode instanceof DataNodeContainer) {
            final List<String> remaining = strings.subList(consumed, strings.size());
            return collectPathArguments(builder, remaining, (DataNodeContainer) targetNode, mountPoint,
                    returnJustMountPoint);
        }

        return createContext(builder.build(), targetNode, mountPoint,
            mountPoint != null ? getModelContext(mountPoint) : this.globalSchema);
    }

    private static InstanceIdentifierContext<?> createContext(final YangInstanceIdentifier instance,
            final DataSchemaNode dataSchemaNode, final DOMMountPoint mountPoint,
            final EffectiveModelContext schemaContext) {
        final YangInstanceIdentifier instanceIdentifier = new DataNormalizer(schemaContext).toNormalized(instance);
        return new InstanceIdentifierContext<>(instanceIdentifier, dataSchemaNode, mountPoint, schemaContext);
    }

    public static DataSchemaNode findInstanceDataChildByNameAndNamespace(final DataNodeContainer container,
            final String name, final URI namespace) {
        requireNonNull(namespace);

        final Iterable<DataSchemaNode> result = Iterables.filter(findInstanceDataChildrenByName(container, name),
            node -> namespace.equals(node.getQName().getNamespace()));
        return Iterables.getFirst(result, null);
    }

    public static List<DataSchemaNode> findInstanceDataChildrenByName(final DataNodeContainer container,
            final String name) {
        final List<DataSchemaNode> instantiatedDataNodeContainers = new ArrayList<>();
        collectInstanceDataNodeContainers(instantiatedDataNodeContainers, requireNonNull(container),
            requireNonNull(name));
        return instantiatedDataNodeContainers;
    }

    private static void collectInstanceDataNodeContainers(final List<DataSchemaNode> potentialSchemaNodes,
            final DataNodeContainer container, final String name) {

        final Iterable<? extends DataSchemaNode> nodes = Iterables.filter(container.getChildNodes(),
            node -> name.equals(node.getQName().getLocalName()));

        // Can't combine this loop with the filter above because the filter is
        // lazily-applied by Iterables.filter.
        for (final DataSchemaNode potentialNode : nodes) {
            if (isInstantiatedDataSchema(potentialNode)) {
                potentialSchemaNodes.add(potentialNode);
            }
        }

        final Iterable<ChoiceSchemaNode> choiceNodes = Iterables.filter(container.getChildNodes(),
            ChoiceSchemaNode.class);
        final Iterable<Collection<? extends CaseSchemaNode>> map = Iterables.transform(choiceNodes,
            ChoiceSchemaNode::getCases);
        for (final CaseSchemaNode caze : Iterables.concat(map)) {
            collectInstanceDataNodeContainers(potentialSchemaNodes, caze, name);
        }
    }

    public static boolean isInstantiatedDataSchema(final DataSchemaNode node) {
        return node instanceof LeafSchemaNode || node instanceof LeafListSchemaNode
                || node instanceof ContainerSchemaNode || node instanceof ListSchemaNode
                || node instanceof AnyxmlSchemaNode;
    }

    private void addKeyValue(final HashMap<QName, Object> map, final DataSchemaNode node, final String uriValue,
            final DOMMountPoint mountPoint) {
        checkArgument(node instanceof LeafSchemaNode);

        final SchemaContext schemaContext = mountPoint == null ? this.globalSchema : getModelContext(mountPoint);
        final String urlDecoded = urlPathArgDecode(requireNonNull(uriValue));
        TypeDefinition<?> typedef = ((LeafSchemaNode) node).getType();
        final TypeDefinition<?> baseType = RestUtil.resolveBaseTypeFrom(typedef);
        if (baseType instanceof LeafrefTypeDefinition) {
            typedef = SchemaContextUtil.getBaseTypeForLeafRef((LeafrefTypeDefinition) baseType, schemaContext, node);
        }
        final IllegalArgumentCodec<Object, Object> codec = RestCodec.from(typedef, mountPoint, this);
        Object decoded = codec.deserialize(urlDecoded);
        String additionalInfo = "";
        if (decoded == null) {
            if (typedef instanceof IdentityrefTypeDefinition) {
                decoded = toQName(schemaContext, urlDecoded);
                additionalInfo =
                        "For key which is of type identityref it should be in format module_name:identity_name.";
            }
        }

        if (decoded == null) {
            throw new RestconfDocumentedException(uriValue + " from URI can't be resolved. " + additionalInfo,
                    ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
        }

        map.put(node.getQName(), decoded);
    }

    private static String toModuleName(final String str) {
        final int idx = str.indexOf(':');
        if (idx == -1) {
            return null;
        }

        // Make sure there is only one occurrence
        if (str.indexOf(':', idx + 1) != -1) {
            return null;
        }

        return str.substring(0, idx);
    }

    private static String toNodeName(final String str) {
        final int idx = str.indexOf(':');
        if (idx == -1) {
            return str;
        }

        // Make sure there is only one occurrence
        if (str.indexOf(':', idx + 1) != -1) {
            return str;
        }

        return str.substring(idx + 1);
    }

    private QName toQName(final SchemaContext schemaContext, final String name,
            final Optional<Revision> revisionDate) {
        checkPreconditions();
        final String module = toModuleName(name);
        final String node = toNodeName(name);
        final Module m = schemaContext.findModule(module, revisionDate).orElse(null);
        return m == null ? null : QName.create(m.getQNameModule(), node);
    }

    private QName toQName(final SchemaContext schemaContext, final String name) {
        checkPreconditions();
        final String module = toModuleName(name);
        final String node = toNodeName(name);
        final Collection<? extends Module> modules = schemaContext.findModules(module);
        return modules.isEmpty() ? null : QName.create(modules.iterator().next().getQNameModule(), node);
    }

    private static boolean isListOrContainer(final DataSchemaNode node) {
        return node instanceof ListSchemaNode || node instanceof ContainerSchemaNode;
    }

    public RpcDefinition getRpcDefinition(final String name, final Optional<Revision> revisionDate) {
        final QName validName = toQName(this.globalSchema, name, revisionDate);
        return validName == null ? null : this.qnameToRpc.get().get(validName);
    }

    public RpcDefinition getRpcDefinition(final String name) {
        final QName validName = toQName(this.globalSchema, name);
        return validName == null ? null : this.qnameToRpc.get().get(validName);
    }

    private static RpcDefinition getRpcDefinition(final Module module, final String rpcName) {
        final QName rpcQName = QName.create(module.getQNameModule(), rpcName);
        for (final RpcDefinition rpcDefinition : module.getRpcs()) {
            if (rpcQName.equals(rpcDefinition.getQName())) {
                return rpcDefinition;
            }
        }
        return null;
    }

    @Override
    public void onModelContextUpdated(final EffectiveModelContext context) {
        if (context != null) {
            final Collection<? extends RpcDefinition> defs = context.getOperations();
            final Map<QName, RpcDefinition> newMap = new HashMap<>(defs.size());

            for (final RpcDefinition operation : defs) {
                newMap.put(operation.getQName(), operation);
            }

            // FIXME: still not completely atomic
            this.qnameToRpc.set(ImmutableMap.copyOf(newMap));
            setGlobalSchema(context);
        }
    }

    private static List<String> urlPathArgsDecode(final Iterable<String> strings) {
        final List<String> decodedPathArgs = new ArrayList<>();
        for (final String pathArg : strings) {
            final String _decode = URLDecoder.decode(pathArg, StandardCharsets.UTF_8);
            decodedPathArgs.add(_decode);
        }
        return decodedPathArgs;
    }

    static String urlPathArgDecode(final String pathArg) {
        if (pathArg == null) {
            return null;
        }
        return URLDecoder.decode(pathArg, StandardCharsets.UTF_8);
    }

    private CharSequence convertToRestconfIdentifier(final PathArgument argument, final DataSchemaNode node,
            final DOMMountPoint mount) {
        if (argument instanceof NodeIdentifier) {
            return convertToRestconfIdentifier((NodeIdentifier) argument, mount);
        } else if (argument instanceof NodeIdentifierWithPredicates && node instanceof ListSchemaNode) {
            return convertToRestconfIdentifierWithPredicates((NodeIdentifierWithPredicates) argument,
                (ListSchemaNode) node, mount);
        } else if (argument != null && node != null) {
            throw new IllegalArgumentException("Conversion of generic path argument is not supported");
        } else {
            throw new IllegalArgumentException("Unhandled parameter types: " + Arrays.asList(argument, node));
        }
    }

    private CharSequence convertToRestconfIdentifier(final NodeIdentifier argument, final DOMMountPoint node) {
        return "/" + toRestconfIdentifier(argument.getNodeType(),node);
    }

    private CharSequence convertToRestconfIdentifierWithPredicates(final NodeIdentifierWithPredicates argument,
            final ListSchemaNode node, final DOMMountPoint mount) {
        final QName nodeType = argument.getNodeType();
        final CharSequence nodeIdentifier = this.toRestconfIdentifier(nodeType, mount);

        final StringBuilder builder = new StringBuilder().append('/').append(nodeIdentifier).append('/');

        final List<QName> keyDefinition = node.getKeyDefinition();
        boolean hasElements = false;
        for (final QName key : keyDefinition) {
            for (final DataSchemaNode listChild : node.getChildNodes()) {
                if (listChild.getQName().equals(key)) {
                    if (!hasElements) {
                        hasElements = true;
                    } else {
                        builder.append('/');
                    }

                    checkState(listChild instanceof LeafSchemaNode,
                        "List key has to consist of leaves, not %s", listChild);

                    final Object value = argument.getValue(key);
                    try {
                        builder.append(toUriString(value, (LeafSchemaNode)listChild, mount));
                    } catch (final UnsupportedEncodingException e) {
                        LOG.error("Error parsing URI: {}", value, e);
                        return null;
                    }
                    break;
                }
            }
        }

        return builder.toString();
    }

    public YangInstanceIdentifier toNormalized(final YangInstanceIdentifier legacy) {
        try {
            return this.dataNormalizer.toNormalized(legacy);
        } catch (final NullPointerException e) {
            throw new RestconfDocumentedException("Data normalizer isn't set. Normalization isn't possible", e);
        }
    }

    public YangInstanceIdentifier toXpathRepresentation(final YangInstanceIdentifier instanceIdentifier) {
        try {
            return this.dataNormalizer.toLegacy(instanceIdentifier);
        } catch (final NullPointerException e) {
            throw new RestconfDocumentedException("Data normalizer isn't set. Normalization isn't possible", e);
        } catch (final DataNormalizationException e) {
            throw new RestconfDocumentedException("Data normalizer failed. Normalization isn't possible", e);
        }
    }

    public boolean isNodeMixin(final YangInstanceIdentifier path) {
        final DataNormalizationOperation<?> operation;
        try {
            operation = this.dataNormalizer.getOperation(path);
        } catch (final DataNormalizationException e) {
            throw new RestconfDocumentedException("Data normalizer failed. Normalization isn't possible", e);
        }
        return operation.isMixin();
    }

    private static EffectiveModelContext getModelContext(final DOMMountPoint mountPoint) {
        return mountPoint.getService(DOMSchemaService.class)
            .flatMap(svc -> Optional.ofNullable(svc.getGlobalContext()))
            .orElse(null);
    }
}
