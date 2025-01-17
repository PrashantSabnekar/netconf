/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.nb.rfc8040.jersey.providers.patch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.InputStream;
import javax.ws.rs.core.MediaType;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opendaylight.restconf.common.errors.RestconfDocumentedException;
import org.opendaylight.restconf.common.patch.PatchContext;
import org.opendaylight.restconf.nb.rfc8040.jersey.providers.test.AbstractBodyReaderTest;
import org.opendaylight.restconf.nb.rfc8040.jersey.providers.test.XmlBodyReaderTest;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;

public class XmlPatchBodyReaderMountPointTest extends AbstractBodyReaderTest {
    private static final String MOUNT_POINT = "instance-identifier-module:cont/yang-ext:mount/";

    private static EffectiveModelContext schemaContext;

    private final XmlToPatchBodyReader xmlToPatchBodyReader;

    public XmlPatchBodyReaderMountPointTest() throws Exception {
        super(schemaContext);
        xmlToPatchBodyReader = new XmlToPatchBodyReader(schemaContextHandler, mountPointService);
    }

    @Override
    protected MediaType getMediaType() {
        return new MediaType(MediaType.APPLICATION_XML, null);
    }

    @BeforeClass
    public static void initialization() {
        schemaContext = schemaContextLoader("/instanceidentifier/yang", schemaContext);
    }

    @Test
    public void moduleDataTest() throws Exception {
        final String uri = MOUNT_POINT + "instance-identifier-patch-module:patch-cont/my-list1=leaf1";
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdata.xml");
        final PatchContext returnValue = xmlToPatchBodyReader
                .readFrom(null, null, null, mediaType, null, inputStream);
        checkPatchContextMountPoint(returnValue);
    }

    /**
     * Test trying to use Patch create operation which requires value without value. Error code 400 should be returned.
     */
    @Test
    public void moduleDataValueMissingNegativeTest() throws Exception {
        final String uri = MOUNT_POINT + "instance-identifier-patch-module:patch-cont/my-list1=leaf1";
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdataValueMissing.xml");
        try {
            xmlToPatchBodyReader.readFrom(null, null, null, mediaType, null, inputStream);
            fail("Test should return error 400 due to missing value node when attempt to invoke create operation");
        } catch (final RestconfDocumentedException e) {
            assertEquals("Error code 400 expected", 400, e.getErrors().get(0).getErrorTag().getStatusCode());
        }
    }

    /**
     * Test trying to use value with Patch delete operation which does not support value. Error code 400 should be
     * returned.
     */
    @Test
    public void moduleDataNotValueNotSupportedNegativeTest() throws Exception {
        final String uri = MOUNT_POINT + "instance-identifier-patch-module:patch-cont/my-list1=leaf1";
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdataValueNotSupported.xml");
        try {
            xmlToPatchBodyReader.readFrom(null, null, null, mediaType, null, inputStream);
            fail("Test should return error 400 due to present value node when attempt to invoke delete operation");
        } catch (final RestconfDocumentedException e) {
            assertEquals("Error code 400 expected", 400, e.getErrors().get(0).getErrorTag().getStatusCode());
        }
    }


    /**
     * Test of Yang Patch with absolute target path.
     */
    @Test
    public void moduleDataAbsoluteTargetPathTest() throws Exception {
        final String uri = MOUNT_POINT;
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdataAbsoluteTargetPath.xml");
        final PatchContext returnValue = xmlToPatchBodyReader
                .readFrom(null, null, null, mediaType, null, inputStream);
        checkPatchContextMountPoint(returnValue);
    }

    /**
     * Test using Patch when target is completely specified in request URI and thus target leaf contains only '/' sign.
     */
    @Test
    public void modulePatchCompleteTargetInURITest() throws Exception {
        final String uri = MOUNT_POINT + "instance-identifier-patch-module:patch-cont";
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdataCompleteTargetInURI.xml");
        final PatchContext returnValue = xmlToPatchBodyReader
                .readFrom(null, null, null, mediaType, null, inputStream);
        checkPatchContextMountPoint(returnValue);
    }

    /**
     * Test of Yang Patch merge operation on list. Test consists of two edit operations - replace and merge.
     */
    @Test
    public void moduleDataMergeOperationOnListTest() throws Exception {
        final String uri = MOUNT_POINT + "instance-identifier-patch-module:patch-cont/my-list1=leaf1";
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdataMergeOperationOnList.xml");
        final PatchContext returnValue = xmlToPatchBodyReader
                .readFrom(null, null, null, mediaType, null, inputStream);
        checkPatchContextMountPoint(returnValue);
    }

    /**
     * Test of Yang Patch merge operation on container. Test consists of two edit operations - create and merge.
     */
    @Test
    public void moduleDataMergeOperationOnContainerTest() throws Exception {
        final String uri = MOUNT_POINT + "instance-identifier-patch-module:patch-cont";
        mockBodyReader(uri, xmlToPatchBodyReader, false);
        final InputStream inputStream = XmlBodyReaderTest.class
                .getResourceAsStream("/instanceidentifier/xml/xmlPATCHdataMergeOperationOnContainer.xml");
        final PatchContext returnValue = xmlToPatchBodyReader
                .readFrom(null, null, null, mediaType, null, inputStream);
        checkPatchContextMountPoint(returnValue);
    }
}
