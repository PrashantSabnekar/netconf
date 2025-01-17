/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.nb.rfc8040.rests.services.impl;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import org.eclipse.jdt.annotation.NonNull;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMDataTreeChangeService;
import org.opendaylight.mdsal.dom.api.DOMDataTreeIdentifier;
import org.opendaylight.mdsal.dom.api.DOMDataTreeReadWriteTransaction;
import org.opendaylight.mdsal.dom.api.DOMDataTreeWriteOperations;
import org.opendaylight.mdsal.dom.api.DOMNotificationListener;
import org.opendaylight.mdsal.dom.api.DOMNotificationService;
import org.opendaylight.mdsal.dom.api.DOMTransactionChain;
import org.opendaylight.restconf.common.errors.RestconfDocumentedException;
import org.opendaylight.restconf.common.errors.RestconfError.ErrorTag;
import org.opendaylight.restconf.common.errors.RestconfError.ErrorType;
import org.opendaylight.restconf.common.util.DataChangeScope;
import org.opendaylight.restconf.nb.rfc8040.Rfc8040;
import org.opendaylight.restconf.nb.rfc8040.rests.services.impl.RestconfStreamsSubscriptionServiceImpl.HandlersHolder;
import org.opendaylight.restconf.nb.rfc8040.rests.services.impl.RestconfStreamsSubscriptionServiceImpl.NotificationQueryParams;
import org.opendaylight.restconf.nb.rfc8040.rests.utils.ResolveEnumUtil;
import org.opendaylight.restconf.nb.rfc8040.rests.utils.RestconfStreamsConstants;
import org.opendaylight.restconf.nb.rfc8040.streams.listeners.ListenerAdapter;
import org.opendaylight.restconf.nb.rfc8040.streams.listeners.ListenersBroker;
import org.opendaylight.restconf.nb.rfc8040.streams.listeners.NotificationListenerAdapter;
import org.opendaylight.restconf.nb.rfc8040.utils.RestconfConstants;
import org.opendaylight.restconf.nb.rfc8040.utils.mapping.RestconfMappingNodeUtil;
import org.opendaylight.restconf.nb.rfc8040.utils.parser.IdentifierCodec;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.stmt.SchemaNodeIdentifier.Absolute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribe to stream util class.
 */
abstract class SubscribeToStreamUtil {
    /**
     * Implementation of SubscribeToStreamUtil for Server-sent events.
     */
    private static final class ServerSentEvents extends SubscribeToStreamUtil {
        static final ServerSentEvents INSTANCE = new ServerSentEvents();

        @Override
        public URI prepareUriByStreamName(final UriInfo uriInfo, final String streamName) {
            final UriBuilder uriBuilder = uriInfo.getBaseUriBuilder();
            return uriBuilder.replacePath(RestconfConstants.BASE_URI_PATTERN + '/'
                    + RestconfConstants.NOTIF + '/' + streamName).build();
        }
    }

    /**
     * Implementation of SubscribeToStreamUtil for Web sockets.
     */
    private static final class WebSockets extends SubscribeToStreamUtil {
        static final WebSockets INSTANCE = new WebSockets();

        @Override
        public URI prepareUriByStreamName(final UriInfo uriInfo, final String streamName) {
            final String scheme = uriInfo.getAbsolutePath().getScheme();
            final UriBuilder uriBuilder = uriInfo.getBaseUriBuilder();
            switch (scheme) {
                case "https":
                    // Secured HTTP goes to Secured WebSockets
                    uriBuilder.scheme("wss");
                    break;
                case "http":
                default:
                    // Unsecured HTTP and others go to unsecured WebSockets
                    uriBuilder.scheme("ws");
            }
            return uriBuilder.replacePath(RestconfConstants.BASE_URI_PATTERN + '/' + streamName).build();
        }
    }


    private static final Logger LOG = LoggerFactory.getLogger(SubscribeToStreamUtil.class);

    SubscribeToStreamUtil() {
        // Hidden on purpose
    }

    static SubscribeToStreamUtil serverSentEvents() {
        return ServerSentEvents.INSTANCE;
    }

    static SubscribeToStreamUtil webSockets() {
        return WebSockets.INSTANCE;
    }

    /**
     * Prepare URL from base name and stream name.
     *
     * @param uriInfo base URL information
     * @param streamName name of stream for create
     * @return final URL
     */
    abstract @NonNull URI prepareUriByStreamName(UriInfo uriInfo, String streamName);

    /**
     * Register listener by streamName in identifier to listen to yang notifications, and put or delete information
     * about listener to DS according to ietf-restconf-monitoring.
     *
     * @param identifier              Name of the stream.
     * @param uriInfo                 URI information.
     * @param notificationQueryParams Query parameters of notification.
     * @param handlersHolder          Holder of handlers for notifications.
     * @return Stream location for listening.
     */
    final @NonNull URI subscribeToYangStream(final String identifier, final UriInfo uriInfo,
            final NotificationQueryParams notificationQueryParams, final HandlersHolder handlersHolder) {
        final String streamName = ListenersBroker.createStreamNameFromUri(identifier);
        if (Strings.isNullOrEmpty(streamName)) {
            throw new RestconfDocumentedException("Stream name is empty.", ErrorType.PROTOCOL, ErrorTag.INVALID_VALUE);
        }

        final NotificationListenerAdapter notificationListenerAdapter = ListenersBroker.getInstance()
            .getNotificationListenerFor(streamName)
            .orElseThrow(() -> new RestconfDocumentedException(
                String.format("Stream with name %s was not found.", streamName),
                ErrorType.PROTOCOL, ErrorTag.UNKNOWN_ELEMENT));

        final DOMTransactionChain transactionChain = handlersHolder.getTransactionChainHandler().get();
        final DOMDataTreeReadWriteTransaction writeTransaction = transactionChain.newReadWriteTransaction();
        final EffectiveModelContext schemaContext = handlersHolder.getSchemaHandler().get();

        final URI uri = prepareUriByStreamName(uriInfo, streamName);
        registerToListenNotification(notificationListenerAdapter, handlersHolder.getNotificationServiceHandler());
        notificationListenerAdapter.setQueryParams(
                notificationQueryParams.getStart(),
                notificationQueryParams.getStop().orElse(null),
                notificationQueryParams.getFilter().orElse(null),
                false, notificationQueryParams.isSkipNotificationData());
        notificationListenerAdapter.setCloseVars(
                handlersHolder.getTransactionChainHandler(), handlersHolder.getSchemaHandler());
        final MapEntryNode mapToStreams = RestconfMappingNodeUtil.mapYangNotificationStreamByIetfRestconfMonitoring(
                    notificationListenerAdapter.getSchemaPath().lastNodeIdentifier(),
                    schemaContext.getNotifications(), notificationQueryParams.getStart(),
                    notificationListenerAdapter.getOutputType(), uri);
        writeDataToDS(writeTransaction, mapToStreams);
        submitData(writeTransaction);
        transactionChain.close();
        return uri;
    }

    /**
     * Register listener by streamName in identifier to listen to data change notifications, and put or delete
     * information about listener to DS according to ietf-restconf-monitoring.
     *
     * @param identifier              Identifier as stream name.
     * @param uriInfo                 Base URI information.
     * @param notificationQueryParams Query parameters of notification.
     * @param handlersHolder          Holder of handlers for notifications.
     * @return Location for listening.
     */
    final URI subscribeToDataStream(final String identifier, final UriInfo uriInfo,
            final NotificationQueryParams notificationQueryParams, final HandlersHolder handlersHolder) {
        final Map<String, String> mapOfValues = mapValuesFromUri(identifier);
        final LogicalDatastoreType datastoreType = parseURIEnum(
                LogicalDatastoreType.class,
                mapOfValues.get(RestconfStreamsConstants.DATASTORE_PARAM_NAME));
        if (datastoreType == null) {
            final String message = "Stream name doesn't contain datastore value (pattern /datastore=)";
            LOG.debug(message);
            throw new RestconfDocumentedException(message, ErrorType.APPLICATION, ErrorTag.MISSING_ATTRIBUTE);
        }

        final DataChangeScope scope = parseURIEnum(
                DataChangeScope.class,
                mapOfValues.get(RestconfStreamsConstants.SCOPE_PARAM_NAME));
        if (scope == null) {
            final String message = "Stream name doesn't contains datastore value (pattern /scope=)";
            LOG.warn(message);
            throw new RestconfDocumentedException(message, ErrorType.APPLICATION, ErrorTag.MISSING_ATTRIBUTE);
        }

        final String streamName = ListenersBroker.createStreamNameFromUri(identifier);
        final Optional<ListenerAdapter> listener = ListenersBroker.getInstance().getDataChangeListenerFor(streamName);
        Preconditions.checkArgument(listener.isPresent(), "Listener doesn't exist : " + streamName);

        listener.get().setQueryParams(
                notificationQueryParams.getStart(),
                notificationQueryParams.getStop().orElse(null),
                notificationQueryParams.getFilter().orElse(null),
                false, notificationQueryParams.isSkipNotificationData());
        listener.get().setCloseVars(handlersHolder.getTransactionChainHandler(), handlersHolder.getSchemaHandler());
        registration(datastoreType, listener.get(), handlersHolder.getDataBroker());

        final URI uri = prepareUriByStreamName(uriInfo, streamName);
        final DOMTransactionChain transactionChain = handlersHolder.getTransactionChainHandler().get();
        final DOMDataTreeReadWriteTransaction writeTransaction = transactionChain.newReadWriteTransaction();
        final EffectiveModelContext schemaContext = handlersHolder.getSchemaHandler().get();
        final String serializedPath = IdentifierCodec.serialize(listener.get().getPath(), schemaContext);

        final MapEntryNode mapToStreams =
            RestconfMappingNodeUtil.mapDataChangeNotificationStreamByIetfRestconfMonitoring(listener.get().getPath(),
                notificationQueryParams.getStart(), listener.get().getOutputType(), uri, schemaContext, serializedPath);
        writeDataToDS(writeTransaction, mapToStreams);
        submitData(writeTransaction);
        transactionChain.close();
        return uri;
    }

    // FIXME: callers are utter duplicates, refactor them
    private static void writeDataToDS(final DOMDataTreeWriteOperations tx, final MapEntryNode mapToStreams) {
        // FIXME: use put() here
        tx.merge(LogicalDatastoreType.OPERATIONAL, Rfc8040.restconfStateStreamPath(mapToStreams.getIdentifier()),
            mapToStreams);
    }

    private static void submitData(final DOMDataTreeReadWriteTransaction readWriteTransaction) {
        try {
            readWriteTransaction.commit().get();
        } catch (final InterruptedException | ExecutionException e) {
            throw new RestconfDocumentedException("Problem while putting data to DS.", e);
        }
    }

    /**
     * Prepare map of URI parameter-values.
     *
     * @param identifier String identification of URI.
     * @return Map od URI parameters and values.
     */
    private static Map<String, String> mapValuesFromUri(final String identifier) {
        final HashMap<String, String> result = new HashMap<>();
        for (final String token : RestconfConstants.SLASH_SPLITTER.split(identifier)) {
            final String[] paramToken = token.split("=");
            if (paramToken.length == 2) {
                result.put(paramToken[0], paramToken[1]);
            }
        }
        return result;
    }

    /**
     * Register data change listener in DOM data broker and set it to listener on stream.
     *
     * @param datastore     {@link LogicalDatastoreType}
     * @param listener      listener on specific stream
     * @param domDataBroker data broker for register data change listener
     */
    private static void registration(final LogicalDatastoreType datastore, final ListenerAdapter listener,
            final DOMDataBroker domDataBroker) {
        if (listener.isListening()) {
            return;
        }

        final DOMDataTreeChangeService changeService = domDataBroker.getExtensions()
                .getInstance(DOMDataTreeChangeService.class);
        if (changeService == null) {
            throw new UnsupportedOperationException("DOMDataBroker does not support the DOMDataTreeChangeService");
        }

        final DOMDataTreeIdentifier root = new DOMDataTreeIdentifier(datastore, listener.getPath());
        final ListenerRegistration<ListenerAdapter> registration =
                changeService.registerDataTreeChangeListener(root, listener);
        listener.setRegistration(registration);
    }

    // FIXME: this method should be in NotificationListenerAdapter
    private static void registerToListenNotification(final NotificationListenerAdapter listener,
            final DOMNotificationService notificationService) {
        if (listener.isListening()) {
            return;
        }

        final Absolute path = listener.getSchemaPath();
        final ListenerRegistration<DOMNotificationListener> registration =
                notificationService.registerNotificationListener(listener, path);
        listener.setRegistration(registration);
    }

    /**
     * Parse out enumeration from URI.
     *
     * @param clazz Target enumeration type.
     * @param value String representation of enumeration value.
     * @return Parsed enumeration type.
     */
    private static <T> T parseURIEnum(final Class<T> clazz, final String value) {
        if (value == null || value.equals("")) {
            return null;
        }
        return ResolveEnumUtil.resolveEnum(clazz, value);
    }
}
