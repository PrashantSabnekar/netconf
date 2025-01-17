/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.nb.rfc8040.utils.mapping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opendaylight.restconf.nb.rfc8040.Rfc8040;
import org.opendaylight.restconf.nb.rfc8040.Rfc8040.IetfYangLibrary;
import org.opendaylight.restconf.nb.rfc8040.TestRestconfUtils;
import org.opendaylight.restconf.nb.rfc8040.utils.parser.ParserIdentifier;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.restconf.monitoring.rev170126.RestconfState;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.restconf.monitoring.rev170126.restconf.state.Capabilities;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.Revision;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.LeafNode;
import org.opendaylight.yangtools.yang.data.api.schema.LeafSetEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.LeafSetNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapNode;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.test.util.YangParserTestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link RestconfMappingNodeUtil}.
 */
public class RestconfMappingNodeUtilTest {
    private static final Logger LOG = LoggerFactory.getLogger(RestconfMappingNodeUtilTest.class);

    private static Collection<? extends Module> modules;
    private static EffectiveModelContext schemaContext;
    private static EffectiveModelContext schemaContextMonitoring;
    private static Collection<? extends Module> modulesRest;

    @BeforeClass
    public static void loadTestSchemaContextAndModules() throws Exception {
        // FIXME: assemble these from dependencies
        schemaContext =
                YangParserTestUtils.parseYangFiles(TestRestconfUtils.loadFiles("/modules/restconf-module-testing"));
        schemaContextMonitoring = YangParserTestUtils.parseYangFiles(TestRestconfUtils.loadFiles("/modules"));
        modules = schemaContextMonitoring.getModules();
        modulesRest = YangParserTestUtils
                .parseYangFiles(TestRestconfUtils.loadFiles("/modules/restconf-module-testing")).getModules();
    }

    /**
     * Test of writing modules into {@link RestconfModule#MODULE_LIST_SCHEMA_NODE} and checking if modules were
     * correctly written.
     */
    @Test
    public void restconfMappingNodeTest() {
        // write modules into list module in Restconf
        final ContainerNode mods = RestconfMappingNodeUtil.mapModulesByIetfYangLibraryYang(
            RestconfMappingNodeUtilTest.modules, schemaContext, "1");

        // verify loaded modules
        verifyLoadedModules(mods);
        // verify deviations
        verifyDeviations(mods);
    }

    @Test
    public void restconfStateCapabilitesTest() {
        final Module monitoringModule = schemaContextMonitoring.findModule(RestconfState.QNAME.getModule()).get();
        final ContainerNode normNode = RestconfMappingNodeUtil.mapCapabilites(monitoringModule);
        assertNotNull(normNode);
        final List<Object> listOfValues = new ArrayList<>();

        for (final DataContainerChild<?, ?> child : normNode.getValue()) {
            if (child.getNodeType().equals(Capabilities.QNAME)) {
                for (final DataContainerChild<?, ?> dataContainerChild : ((ContainerNode) child).getValue()) {
                    for (final Object entry : ((LeafSetNode<?>) dataContainerChild).getValue()) {
                        listOfValues.add(((LeafSetEntryNode<?>) entry).getValue());
                    }
                }
            }
        }
        assertTrue(listOfValues.contains(Rfc8040.Capabilities.DEPTH));
        assertTrue(listOfValues.contains(Rfc8040.Capabilities.FIELDS));
        assertTrue(listOfValues.contains(Rfc8040.Capabilities.FILTER));
        assertTrue(listOfValues.contains(Rfc8040.Capabilities.REPLAY));
        assertTrue(listOfValues.contains(Rfc8040.Capabilities.WITH_DEFAULTS));
    }

    @Test
    public void toStreamEntryNodeTest() throws Exception {
        final YangInstanceIdentifier path = ParserIdentifier.toInstanceIdentifier(
                "nested-module:depth1-cont/depth2-leaf1", schemaContextMonitoring, null).getInstanceIdentifier();
        final Instant start = Instant.now();
        final String outputType = "XML";
        final URI uri = new URI("uri");
        final String streamName = "/nested-module:depth1-cont/depth2-leaf1";

        final Map<QName, Object> map = prepareMap(streamName, uri, start, outputType);
        final MapEntryNode mappedData = RestconfMappingNodeUtil.mapDataChangeNotificationStreamByIetfRestconfMonitoring(
            path, start, outputType, uri, schemaContextMonitoring, streamName);
        assertMappedData(map, mappedData);
    }

    @Test
    public void toStreamEntryNodeNotifiTest() throws Exception {
        final Instant start = Instant.now();
        final String outputType = "JSON";
        final URI uri = new URI("uri");

        final Map<QName, Object> map = prepareMap("notifi", uri, start, outputType);
        map.put(RestconfMappingNodeUtil.DESCRIPTION_QNAME, "Notifi");

        final QName notifiQName = QName.create("urn:nested:module", "2014-06-03", "notifi");
        final MapEntryNode mappedData = RestconfMappingNodeUtil.mapYangNotificationStreamByIetfRestconfMonitoring(
            notifiQName, schemaContextMonitoring.getNotifications(), start, outputType, uri);
        assertMappedData(map, mappedData);
    }

    private static Map<QName, Object> prepareMap(final String name, final URI uri, final Instant start,
            final String outputType) {
        final Map<QName, Object> map = new HashMap<>();
        map.put(RestconfMappingNodeUtil.NAME_QNAME, name);
        map.put(RestconfMappingNodeUtil.LOCATION_QNAME, uri.toString());
        map.put(RestconfMappingNodeUtil.REPLAY_SUPPORT_QNAME, Boolean.TRUE);
        map.put(RestconfMappingNodeUtil.REPLAY_LOG_CREATION_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            OffsetDateTime.ofInstant(start, ZoneId.systemDefault())));
        map.put(RestconfMappingNodeUtil.ENCODING_QNAME, outputType);
        return map;
    }

    private static void assertMappedData(final Map<QName, Object> map, final MapEntryNode mappedData) {
        assertNotNull(mappedData);
        for (final DataContainerChild<?, ?> child : mappedData.getValue()) {
            if (child instanceof LeafNode) {
                final LeafNode<?> leaf = (LeafNode<?>) child;
                assertTrue(map.containsKey(leaf.getNodeType()));
                assertEquals(map.get(leaf.getNodeType()), leaf.getValue());
            }
        }
    }

    /**
     * Verify whether the loaded modules contain any deviations.
     *
     * @param containerNode
     *             modules
     */
    private static void verifyDeviations(final ContainerNode containerNode) {
        int deviationsFound = 0;
        for (final DataContainerChild<?, ?> child : containerNode.getValue()) {
            if (child instanceof MapNode) {
                for (final MapEntryNode mapEntryNode : ((MapNode) child).getValue()) {
                    for (final DataContainerChild<?, ?> dataContainerChild : mapEntryNode.getValue()) {
                        if (dataContainerChild.getNodeType()
                                .equals(IetfYangLibrary.SPECIFIC_MODULE_DEVIATION_LIST_QNAME)) {
                            deviationsFound++;
                        }
                    }
                }
            }
        }
        assertTrue(deviationsFound > 0);
    }

    /**
     * Verify loaded modules.
     *
     * @param containerNode
     *             modules
     */
    private static void verifyLoadedModules(final ContainerNode containerNode) {

        final Map<String, String> loadedModules = new HashMap<>();

        for (final DataContainerChild<? extends PathArgument, ?> child : containerNode.getValue()) {
            if (child instanceof LeafNode) {
                assertEquals(IetfYangLibrary.MODULE_SET_ID_LEAF_QNAME, child.getNodeType());
            }
            if (child instanceof MapNode) {
                assertEquals(IetfYangLibrary.MODULE_QNAME_LIST, child.getNodeType());
                for (final MapEntryNode mapEntryNode : ((MapNode) child).getValue()) {
                    String name = "";
                    String revision = "";
                    for (final DataContainerChild<? extends PathArgument, ?> dataContainerChild : mapEntryNode
                            .getValue()) {
                        switch (dataContainerChild.getNodeType().getLocalName()) {
                            case IetfYangLibrary.SPECIFIC_MODULE_NAME_LEAF:
                                name = String.valueOf(dataContainerChild.getValue());
                                break;
                            case IetfYangLibrary.SPECIFIC_MODULE_REVISION_LEAF:
                                revision = String.valueOf(dataContainerChild.getValue());
                                break;
                            default :
                                LOG.info("Unknown local name '{}' of node.",
                                        dataContainerChild.getNodeType().getLocalName());
                                break;
                        }
                    }
                    loadedModules.put(name, revision);
                }
            }
        }

        verifyLoadedModules(RestconfMappingNodeUtilTest.modulesRest, loadedModules);
    }

    /**
     * Verify if correct modules were loaded into Restconf module by comparison with modules from
     * <code>SchemaContext</code>.
     * @param expectedModules Modules from <code>SchemaContext</code>
     * @param loadedModules Loaded modules into Restconf module
     */
    private static void verifyLoadedModules(final Collection<? extends Module> expectedModules,
            final Map<String, String> loadedModules) {
        assertEquals("Number of loaded modules is not as expected", expectedModules.size(), loadedModules.size());
        for (final Module m : expectedModules) {
            final String name = m.getName();

            final String revision = loadedModules.get(name);
            assertNotNull("Expected module not found", revision);
            assertEquals("Incorrect revision of loaded module", Revision.ofNullable(revision), m.getRevision());

            loadedModules.remove(name);
        }
    }
}
