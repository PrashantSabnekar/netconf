/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.nb.rfc8040.rests.services.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.opendaylight.restconf.common.patch.PatchEditOperation.CREATE;
import static org.opendaylight.restconf.common.patch.PatchEditOperation.DELETE;
import static org.opendaylight.restconf.common.patch.PatchEditOperation.REMOVE;
import static org.opendaylight.restconf.common.patch.PatchEditOperation.REPLACE;
import static org.opendaylight.yangtools.util.concurrent.FluentFutures.immediateFalseFluentFuture;
import static org.opendaylight.yangtools.util.concurrent.FluentFutures.immediateFluentFuture;
import static org.opendaylight.yangtools.util.concurrent.FluentFutures.immediateTrueFluentFuture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.dom.api.DOMActionService;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMDataTreeReadTransaction;
import org.opendaylight.mdsal.dom.api.DOMDataTreeReadWriteTransaction;
import org.opendaylight.mdsal.dom.api.DOMDataTreeWriteTransaction;
import org.opendaylight.mdsal.dom.api.DOMMountPoint;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.mdsal.dom.api.DOMTransactionChain;
import org.opendaylight.mdsal.dom.spi.FixedDOMSchemaService;
import org.opendaylight.netconf.dom.api.NetconfDataTreeService;
import org.opendaylight.restconf.common.context.InstanceIdentifierContext;
import org.opendaylight.restconf.common.context.NormalizedNodeContext;
import org.opendaylight.restconf.common.errors.RestconfDocumentedException;
import org.opendaylight.restconf.common.patch.PatchContext;
import org.opendaylight.restconf.common.patch.PatchEntity;
import org.opendaylight.restconf.common.patch.PatchStatusContext;
import org.opendaylight.restconf.nb.rfc8040.TestRestconfUtils;
import org.opendaylight.restconf.nb.rfc8040.handlers.SchemaContextHandler;
import org.opendaylight.restconf.nb.rfc8040.handlers.TransactionChainHandler;
import org.opendaylight.restconf.nb.rfc8040.rests.services.api.RestconfStreamsSubscriptionService;
import org.opendaylight.restconf.nb.rfc8040.rests.transactions.MdsalRestconfStrategy;
import org.opendaylight.restconf.nb.rfc8040.rests.transactions.NetconfRestconfStrategy;
import org.opendaylight.restconf.nb.rfc8040.rests.transactions.RestconfStrategy;
import org.opendaylight.restconf.nb.rfc8040.streams.Configuration;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifierWithPredicates;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.LeafNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.impl.ImmutableContainerNodeBuilder;
import org.opendaylight.yangtools.yang.data.util.DataSchemaContextTree;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.opendaylight.yangtools.yang.test.util.YangParserTestUtils;

@RunWith(MockitoJUnitRunner.StrictStubs.class)
public class RestconfDataServiceImplTest {

    private static final String PATH_FOR_NEW_SCHEMA_CONTEXT = "/jukebox";

    private ContainerNode buildBaseCont;
    private ContainerNode buildBaseContConfig;
    private ContainerNode buildBaseContOperational;
    private EffectiveModelContext contextRef;
    private YangInstanceIdentifier iidBase;
    private DataSchemaNode schemaNode;
    private RestconfDataServiceImpl dataService;
    private QName baseQName;
    private QName containerPlayerQname;
    private QName leafQname;
    private ContainerNode buildPlayerCont;
    private ContainerNode buildLibraryCont;
    private MapNode buildPlaylistList;

    @Mock
    private DOMTransactionChain domTransactionChain;
    @Mock
    private UriInfo uriInfo;
    @Mock
    private DOMDataTreeReadWriteTransaction readWrite;
    @Mock
    private DOMDataTreeReadTransaction read;
    @Mock
    private DOMDataTreeWriteTransaction write;
    @Mock
    private DOMMountPointService mountPointService;
    @Mock
    private DOMMountPoint mountPoint;
    @Mock
    private DOMDataBroker mountDataBroker;
    @Mock
    private NetconfDataTreeService netconfService;
    @Mock
    private DOMActionService actionService;
    @Mock
    private RestconfStreamsSubscriptionService delegRestconfSubscrService;
    @Mock
    private Configuration configuration;
    @Mock
    private MultivaluedMap<String, String> queryParamenters;

    @Before
    public void setUp() throws Exception {
        doReturn(Set.of()).when(queryParamenters).entrySet();
        doReturn(queryParamenters).when(this.uriInfo).getQueryParameters();

        this.baseQName = QName.create("http://example.com/ns/example-jukebox", "2015-04-04", "jukebox");
        this.containerPlayerQname = QName.create(this.baseQName, "player");
        this.leafQname = QName.create(this.baseQName, "gap");

        final QName containerLibraryQName = QName.create(this.baseQName, "library");
        final QName listPlaylistQName = QName.create(this.baseQName, "playlist");

        final LeafNode<?> buildLeaf = Builders.leafBuilder()
                .withNodeIdentifier(new NodeIdentifier(this.leafQname))
                .withValue(0.2)
                .build();

        this.buildPlayerCont = Builders.containerBuilder()
                .withNodeIdentifier(new NodeIdentifier(this.containerPlayerQname))
                .withChild(buildLeaf)
                .build();

        this.buildLibraryCont = Builders.containerBuilder()
                .withNodeIdentifier(new NodeIdentifier(containerLibraryQName))
                .build();

        this.buildPlaylistList = Builders.mapBuilder()
                .withNodeIdentifier(new NodeIdentifier(listPlaylistQName))
                .build();

        this.buildBaseCont = Builders.containerBuilder()
                .withNodeIdentifier(new NodeIdentifier(this.baseQName))
                .withChild(this.buildPlayerCont)
                .build();

        // config contains one child the same as in operational and one additional
        this.buildBaseContConfig = Builders.containerBuilder()
                .withNodeIdentifier(new NodeIdentifier(this.baseQName))
                .withChild(this.buildPlayerCont)
                .withChild(this.buildLibraryCont)
                .build();

        // operational contains one child the same as in config and one additional
        this.buildBaseContOperational = Builders.containerBuilder()
                .withNodeIdentifier(new NodeIdentifier(this.baseQName))
                .withChild(this.buildPlayerCont)
                .withChild(this.buildPlaylistList)
                .build();

        this.iidBase = YangInstanceIdentifier.builder()
                .node(this.baseQName)
                .build();

        this.contextRef =
                YangParserTestUtils.parseYangFiles(TestRestconfUtils.loadFiles(PATH_FOR_NEW_SCHEMA_CONTEXT));
        this.schemaNode = DataSchemaContextTree.from(this.contextRef).findChild(this.iidBase).orElseThrow(
            ).getDataSchemaNode();

        doReturn(CommitInfo.emptyFluentFuture()).when(this.write).commit();
        doReturn(CommitInfo.emptyFluentFuture()).when(this.readWrite).commit();

        doReturn(this.write).when(domTransactionChain).newWriteOnlyTransaction();

        DOMDataBroker mockDataBroker = mock(DOMDataBroker.class);
        doReturn(this.read).when(mockDataBroker).newReadOnlyTransaction();
        doReturn(this.readWrite).when(mockDataBroker).newReadWriteTransaction();
        doReturn(domTransactionChain).when(mockDataBroker).createTransactionChain(any());

        final SchemaContextHandler schemaContextHandler = new SchemaContextHandler(
                new TransactionChainHandler(mockDataBroker), mock(DOMSchemaService.class));

        schemaContextHandler.onModelContextUpdated(this.contextRef);
        this.dataService = new RestconfDataServiceImpl(schemaContextHandler, mockDataBroker, mountPointService,
                this.delegRestconfSubscrService, this.actionService, configuration);
        doReturn(Optional.of(this.mountPoint)).when(this.mountPointService)
                .getMountPoint(any(YangInstanceIdentifier.class));
        doReturn(Optional.of(FixedDOMSchemaService.of(this.contextRef))).when(this.mountPoint)
                .getService(DOMSchemaService.class);
        doReturn(Optional.of(this.mountDataBroker)).when(this.mountPoint).getService(DOMDataBroker.class);
        doReturn(Optional.empty()).when(this.mountPoint).getService(NetconfDataTreeService.class);
        doReturn(this.read).when(this.mountDataBroker).newReadOnlyTransaction();
        doReturn(this.readWrite).when(this.mountDataBroker).newReadWriteTransaction();
    }

    @Test
    public void testReadData() {
        doReturn(new MultivaluedHashMap<String, String>()).when(this.uriInfo).getQueryParameters();
        doReturn(immediateFluentFuture(Optional.of(this.buildBaseCont))).when(this.read)
                .read(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateFluentFuture(Optional.empty()))
                .when(this.read).read(LogicalDatastoreType.OPERATIONAL, this.iidBase);
        final Response response = this.dataService.readData("example-jukebox:jukebox", this.uriInfo);
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(this.buildBaseCont, ((NormalizedNodeContext) response.getEntity()).getData());
    }

    @Test
    public void testReadRootData() {
        doReturn(new MultivaluedHashMap<String, String>()).when(this.uriInfo).getQueryParameters();
        doReturn(immediateFluentFuture(Optional.of(wrapNodeByDataRootContainer(this.buildBaseContConfig))))
                .when(this.read)
                .read(LogicalDatastoreType.CONFIGURATION, YangInstanceIdentifier.empty());
        doReturn(immediateFluentFuture(Optional.of(wrapNodeByDataRootContainer(this.buildBaseContOperational))))
                .when(this.read)
                .read(LogicalDatastoreType.OPERATIONAL, YangInstanceIdentifier.empty());
        final Response response = this.dataService.readData(this.uriInfo);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        final NormalizedNode<?, ?> data = ((NormalizedNodeContext) response.getEntity()).getData();
        assertTrue(data instanceof ContainerNode);
        final Collection<DataContainerChild<? extends PathArgument, ?>> rootNodes = ((ContainerNode) data).getValue();
        assertEquals(1, rootNodes.size());
        final Collection<DataContainerChild<? extends PathArgument, ?>> allDataChildren
                = ((ContainerNode) rootNodes.iterator().next()).getValue();
        assertEquals(3, allDataChildren.size());
    }

    private static ContainerNode wrapNodeByDataRootContainer(final DataContainerChild<?, ?> data) {
        return ImmutableContainerNodeBuilder.create()
                .withNodeIdentifier(NodeIdentifier.create(SchemaContext.NAME))
                .withChild(data)
                .build();
    }

    /**
     * Test read data from mount point when both {@link LogicalDatastoreType#CONFIGURATION} and
     * {@link LogicalDatastoreType#OPERATIONAL} contains the same data and some additional data to be merged.
     */
    @Test
    public void testReadDataMountPoint() {
        doReturn(new MultivaluedHashMap<String, String>()).when(this.uriInfo).getQueryParameters();
        doReturn(immediateFluentFuture(Optional.of(this.buildBaseContConfig))).when(this.read)
                .read(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateFluentFuture(Optional.of(this.buildBaseContOperational))).when(this.read)
                .read(LogicalDatastoreType.OPERATIONAL, this.iidBase);

        final Response response = this.dataService.readData(
                "example-jukebox:jukebox/yang-ext:mount/example-jukebox:jukebox", this.uriInfo);

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        // response must contain all child nodes from config and operational containers merged in one container
        final NormalizedNode<?, ?> data = ((NormalizedNodeContext) response.getEntity()).getData();
        assertTrue(data instanceof ContainerNode);
        assertEquals(3, ((ContainerNode) data).getValue().size());
        assertTrue(((ContainerNode) data).getChild(this.buildPlayerCont.getIdentifier()).isPresent());
        assertTrue(((ContainerNode) data).getChild(this.buildLibraryCont.getIdentifier()).isPresent());
        assertTrue(((ContainerNode) data).getChild(this.buildPlaylistList.getIdentifier()).isPresent());
    }

    @Test(expected = RestconfDocumentedException.class)
    public void testReadDataNoData() {
        doReturn(new MultivaluedHashMap<String, String>()).when(this.uriInfo).getQueryParameters();
        doReturn(immediateFluentFuture(Optional.empty()))
                .when(this.read).read(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateFluentFuture(Optional.empty()))
                .when(this.read).read(LogicalDatastoreType.OPERATIONAL, this.iidBase);
        this.dataService.readData("example-jukebox:jukebox", this.uriInfo);
    }

    /**
     * Read data from config datastore according to content parameter.
     */
    @Test
    public void testReadDataConfigTest() {
        final MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.put("content", List.of("config"));

        doReturn(parameters).when(this.uriInfo).getQueryParameters();
        doReturn(immediateFluentFuture(Optional.of(this.buildBaseContConfig))).when(this.read)
                .read(LogicalDatastoreType.CONFIGURATION, this.iidBase);

        final Response response = this.dataService.readData("example-jukebox:jukebox", this.uriInfo);

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        // response must contain only config data
        final NormalizedNode<?, ?> data = ((NormalizedNodeContext) response.getEntity()).getData();

        // config data present
        assertTrue(((ContainerNode) data).getChild(this.buildPlayerCont.getIdentifier()).isPresent());
        assertTrue(((ContainerNode) data).getChild(this.buildLibraryCont.getIdentifier()).isPresent());

        // state data absent
        assertFalse(((ContainerNode) data).getChild(this.buildPlaylistList.getIdentifier()).isPresent());
    }

    /**
     * Read data from operational datastore according to content parameter.
     */
    @Test
    public void testReadDataOperationalTest() {
        final MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.put("content", List.of("nonconfig"));

        doReturn(parameters).when(this.uriInfo).getQueryParameters();
        doReturn(immediateFluentFuture(Optional.of(this.buildBaseContOperational))).when(this.read)
                .read(LogicalDatastoreType.OPERATIONAL, this.iidBase);

        final Response response = this.dataService.readData("example-jukebox:jukebox", this.uriInfo);

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        // response must contain only operational data
        final NormalizedNode<?, ?> data = ((NormalizedNodeContext) response.getEntity()).getData();

        // state data present
        assertTrue(((ContainerNode) data).getChild(this.buildPlayerCont.getIdentifier()).isPresent());
        assertTrue(((ContainerNode) data).getChild(this.buildPlaylistList.getIdentifier()).isPresent());

        // config data absent
        assertFalse(((ContainerNode) data).getChild(this.buildLibraryCont.getIdentifier()).isPresent());
    }

    @Test
    public void testPutData() {
        final InstanceIdentifierContext<DataSchemaNode> iidContext =
                new InstanceIdentifierContext<>(this.iidBase, this.schemaNode, null, this.contextRef);
        final NormalizedNodeContext payload = new NormalizedNodeContext(iidContext, this.buildBaseCont);

        doReturn(immediateTrueFluentFuture()).when(this.read)
                .exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doNothing().when(this.readWrite).put(LogicalDatastoreType.CONFIGURATION, this.iidBase, payload.getData());
        final Response response = this.dataService.putData(null, payload, this.uriInfo);
        assertNotNull(response);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    }

    @Test
    public void testPutDataWithMountPoint() {
        final InstanceIdentifierContext<DataSchemaNode> iidContext =
                new InstanceIdentifierContext<>(this.iidBase, this.schemaNode, mountPoint, this.contextRef);
        final NormalizedNodeContext payload = new NormalizedNodeContext(iidContext, this.buildBaseCont);

        doReturn(immediateTrueFluentFuture()).when(this.read)
                .exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doNothing().when(this.readWrite).put(LogicalDatastoreType.CONFIGURATION, this.iidBase, payload.getData());
        final Response response = this.dataService.putData(null, payload, this.uriInfo);
        assertNotNull(response);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    }

    @Test
    public void testPostData() {
        final QName listQname = QName.create(this.baseQName, "playlist");
        final QName listKeyQname = QName.create(this.baseQName, "name");
        final NodeIdentifierWithPredicates nodeWithKey =
                NodeIdentifierWithPredicates.of(listQname, listKeyQname, "name of band");
        final LeafNode<Object> content = Builders.leafBuilder()
                .withNodeIdentifier(new NodeIdentifier(QName.create(this.baseQName, "name")))
                .withValue("name of band")
                .build();
        final LeafNode<Object> content2 = Builders.leafBuilder()
            .withNodeIdentifier(new NodeIdentifier(QName.create(this.baseQName, "description")))
            .withValue("band description")
            .build();
        final MapEntryNode mapEntryNode = Builders.mapEntryBuilder()
                .withNodeIdentifier(nodeWithKey)
                .withChild(content)
                .withChild(content2)
                .build();
        final MapNode buildList = Builders.mapBuilder()
                .withNodeIdentifier(new NodeIdentifier(listQname))
                .withChild(mapEntryNode)
                .build();

        doReturn(new MultivaluedHashMap<String, String>()).when(this.uriInfo).getQueryParameters();
        final InstanceIdentifierContext<? extends SchemaNode> iidContext =
                new InstanceIdentifierContext<>(this.iidBase, null, null, this.contextRef);
        final NormalizedNodeContext payload = new NormalizedNodeContext(iidContext, buildList);
        final MapNode data = (MapNode) payload.getData();
        final MapEntryNode entryNode = data.getValue().iterator().next();
        final NodeIdentifierWithPredicates identifier = entryNode.getIdentifier();
        final YangInstanceIdentifier node =
                payload.getInstanceIdentifierContext().getInstanceIdentifier().node(identifier);
        doReturn(immediateFalseFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, node);
        doNothing().when(this.readWrite).put(LogicalDatastoreType.CONFIGURATION, node, entryNode);
        doReturn(UriBuilder.fromUri("http://localhost:8181/restconf/15/")).when(this.uriInfo).getBaseUriBuilder();

        final Response response = this.dataService.postData(null, payload, this.uriInfo);
        assertEquals(201, response.getStatus());
    }

    @Test
    public void testDeleteData() {
        doNothing().when(this.readWrite).delete(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateTrueFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        final Response response = this.dataService.deleteData("example-jukebox:jukebox");
        assertNotNull(response);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    }

    /**
     * Test of deleting data on mount point.
     */
    @Test
    public void testDeleteDataMountPoint() {
        doNothing().when(this.readWrite).delete(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateTrueFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        final Response response =
                this.dataService.deleteData("example-jukebox:jukebox/yang-ext:mount/example-jukebox:jukebox");
        assertNotNull(response);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    }

    @Test
    public void testPatchData() {
        final InstanceIdentifierContext<? extends SchemaNode> iidContext =
                new InstanceIdentifierContext<>(this.iidBase, this.schemaNode, null, this.contextRef);
        final List<PatchEntity> entity = new ArrayList<>();
        final YangInstanceIdentifier iidleaf = YangInstanceIdentifier.builder(this.iidBase)
                .node(this.containerPlayerQname)
                .node(this.leafQname)
                .build();
        entity.add(new PatchEntity("create data", CREATE, this.iidBase, this.buildBaseCont));
        entity.add(new PatchEntity("replace data", REPLACE, this.iidBase, this.buildBaseCont));
        entity.add(new PatchEntity("delete data", DELETE, iidleaf));
        final PatchContext patch = new PatchContext(iidContext, entity, "test patch id");

        doNothing().when(this.readWrite).delete(LogicalDatastoreType.CONFIGURATION, iidleaf);
        doReturn(immediateFalseFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateTrueFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, iidleaf);
        final PatchStatusContext status = this.dataService.patchData(patch, this.uriInfo);
        assertTrue(status.isOk());
        assertEquals(3, status.getEditCollection().size());
        assertEquals("replace data", status.getEditCollection().get(1).getEditId());
    }

    @Test
    public void testPatchDataMountPoint() throws Exception {
        final InstanceIdentifierContext<? extends SchemaNode> iidContext = new InstanceIdentifierContext<>(
                this.iidBase, this.schemaNode, this.mountPoint, this.contextRef);
        final List<PatchEntity> entity = new ArrayList<>();
        final YangInstanceIdentifier iidleaf = YangInstanceIdentifier.builder(this.iidBase)
                .node(this.containerPlayerQname)
                .node(this.leafQname)
                .build();
        entity.add(new PatchEntity("create data", CREATE, this.iidBase, this.buildBaseCont));
        entity.add(new PatchEntity("replace data", REPLACE, this.iidBase, this.buildBaseCont));
        entity.add(new PatchEntity("delete data", DELETE, iidleaf));
        final PatchContext patch = new PatchContext(iidContext, entity, "test patch id");

        doNothing().when(this.readWrite).delete(LogicalDatastoreType.CONFIGURATION, iidleaf);
        doReturn(immediateFalseFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateTrueFluentFuture()).when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, iidleaf);

        final PatchStatusContext status = this.dataService.patchData(patch, this.uriInfo);
        assertTrue(status.isOk());
        assertEquals(3, status.getEditCollection().size());
        assertNull(status.getGlobalErrors());
    }

    @Test
    public void testPatchDataDeleteNotExist() {
        final InstanceIdentifierContext<? extends SchemaNode> iidContext =
                new InstanceIdentifierContext<>(this.iidBase, this.schemaNode, null, this.contextRef);
        final List<PatchEntity> entity = new ArrayList<>();
        final YangInstanceIdentifier iidleaf = YangInstanceIdentifier.builder(this.iidBase)
                .node(this.containerPlayerQname)
                .node(this.leafQname)
                .build();
        entity.add(new PatchEntity("create data", CREATE, this.iidBase, this.buildBaseCont));
        entity.add(new PatchEntity("remove data", REMOVE, iidleaf));
        entity.add(new PatchEntity("delete data", DELETE, iidleaf));
        final PatchContext patch = new PatchContext(iidContext, entity, "test patch id");

        doNothing().when(this.readWrite).delete(LogicalDatastoreType.CONFIGURATION, iidleaf);
        doReturn(immediateFalseFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, this.iidBase);
        doReturn(immediateFalseFluentFuture())
                .when(this.readWrite).exists(LogicalDatastoreType.CONFIGURATION, iidleaf);
        doReturn(true).when(this.readWrite).cancel();
        final PatchStatusContext status = this.dataService.patchData(patch, this.uriInfo);

        assertFalse(status.isOk());
        assertEquals(3, status.getEditCollection().size());
        assertTrue(status.getEditCollection().get(0).isOk());
        assertTrue(status.getEditCollection().get(1).isOk());
        assertFalse(status.getEditCollection().get(2).isOk());
        assertFalse(status.getEditCollection().get(2).getEditErrors().isEmpty());
        final String errorMessage = status.getEditCollection().get(2).getEditErrors().get(0).getErrorMessage();
        assertEquals("Data does not exist", errorMessage);
    }

    @Test
    public void testGetRestconfStrategy() {
        RestconfStrategy restconfStrategy = this.dataService.getRestconfStrategy(this.mountPoint);
        assertTrue(restconfStrategy instanceof MdsalRestconfStrategy);

        doReturn(Optional.of(this.netconfService)).when(this.mountPoint).getService(NetconfDataTreeService.class);
        restconfStrategy = this.dataService.getRestconfStrategy(this.mountPoint);
        assertTrue(restconfStrategy instanceof NetconfRestconfStrategy);
    }
}
