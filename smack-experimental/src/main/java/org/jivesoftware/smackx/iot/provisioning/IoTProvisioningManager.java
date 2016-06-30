/**
 *
 * Copyright 2016 Florian Schmaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.iot.provisioning;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jivesoftware.smack.ConnectionCreationListener;
import org.jivesoftware.smack.Manager;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPConnectionRegistry;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.StanzaExtensionFilter;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.filter.StanzaTypeFilter;
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler;
import org.jivesoftware.smack.iqrequest.IQRequestHandler.Mode;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.roster.SubscribeListener;
import org.jivesoftware.smack.util.Objects;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.iot.discovery.IoTDiscoveryManager;
import org.jivesoftware.smackx.iot.provisioning.element.ClearCache;
import org.jivesoftware.smackx.iot.provisioning.element.ClearCacheResponse;
import org.jivesoftware.smackx.iot.provisioning.element.Constants;
import org.jivesoftware.smackx.iot.provisioning.element.IoTIsFriend;
import org.jivesoftware.smackx.iot.provisioning.element.IoTIsFriendResponse;
import org.jivesoftware.smackx.iot.provisioning.element.Unfriend;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.Jid;

/**
 * A manager for XEP-0324: Internet of Things - Provisioning.
 *
 * @author Florian Schmaus {@literal <flo@geekplace.eu>}
 * @see <a href="http://xmpp.org/extensions/xep-0324.html">XEP-0324: Internet of Things - Provisioning</a>
 */
public final class IoTProvisioningManager extends Manager {

    private static final Logger LOGGER = Logger.getLogger(IoTProvisioningManager.class.getName());

    private static final StanzaFilter UNFRIEND_MESSAGE = new AndFilter(StanzaTypeFilter.MESSAGE,
                    new StanzaExtensionFilter(Unfriend.ELEMENT, Unfriend.NAMESPACE));

    private static final Map<XMPPConnection, IoTProvisioningManager> INSTANCES = new WeakHashMap<>();

    // Ensure a IoTProvisioningManager exists for every connection.
    static {
        XMPPConnectionRegistry.addConnectionCreationListener(new ConnectionCreationListener() {
            public void connectionCreated(XMPPConnection connection) {
                getInstanceFor(connection);
            }
        });
    }

    public static synchronized IoTProvisioningManager getInstanceFor(XMPPConnection connection) {
        IoTProvisioningManager manager = INSTANCES.get(connection);
        if (manager == null) {
            manager = new IoTProvisioningManager(connection);
            INSTANCES.put(connection, manager);
        }
        return manager;
    }

    private final Roster roster;

    private Jid configuredProvisioningServer;

    private IoTProvisioningManager(XMPPConnection connection) {
        super(connection);

        // Stanza listener for XEP-0324 § 3.2.3.
        connection.addAsyncStanzaListener(new StanzaListener() {
            @Override
            public void processPacket(Stanza stanza) throws NotConnectedException, InterruptedException {
                if (!isFromProvisioningService(stanza)) {
                    return;
                }

                Message message = (Message) stanza;
                Unfriend unfriend = Unfriend.from(message);
                BareJid unfriendJid = unfriend.getJid();
                final XMPPConnection connection = connection();
                Roster roster = Roster.getInstanceFor(connection);
                if (!roster.isSubscribedToMyPresence(unfriendJid)) {
                    LOGGER.warning("Ignoring <unfriend/> request '" + stanza + "' because " + unfriendJid
                                    + " is already not subscribed to our presence.");
                    return;
                }
                Presence unsubscribed = new Presence(Presence.Type.unsubscribed);
                unsubscribed.setTo(unfriendJid);
                connection.sendStanza(unsubscribed);
            }
        }, UNFRIEND_MESSAGE);

        connection.registerIQRequestHandler(
                        new AbstractIqRequestHandler(ClearCache.ELEMENT, ClearCache.NAMESPACE, Type.set, Mode.async) {
                            @Override
                            public IQ handleIQRequest(IQ iqRequest) {
                                if (!isFromProvisioningService(iqRequest)) {
                                    return null;
                                }

                                ClearCache clearCache = (ClearCache) iqRequest;

                                // TODO Handle clear cache request.

                                return new ClearCacheResponse(clearCache);
                            }
                        });

        roster = Roster.getInstanceFor(connection);
        roster.setSubscribeListener(new SubscribeListener() {
            @Override
            public SubscribeAnswer processSubscribe(Jid from, Presence subscribeRequest) {
                // First check if the subscription request comes from a known registry and accept the request if so.
                try {
                    if (IoTDiscoveryManager.getInstanceFor(connection()).isRegistry(from.asBareJid())) {
                        return SubscribeAnswer.Approve;
                    }
                }
                catch (NoResponseException | XMPPErrorException | NotConnectedException | InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Could not determine if " + from + " is a registry", e);
                }

                Jid provisioningServer = null;
                try {
                    provisioningServer = getConfiguredProvisioningServer();
                }
                catch (NoResponseException | XMPPErrorException | NotConnectedException | InterruptedException e) {
                    LOGGER.log(Level.WARNING,
                                    "Could not determine privisioning server. Ignoring friend request from " + from, e);
                }
                if (provisioningServer == null) {
                    return null;
                }

                boolean isFriend;
                try {
                    isFriend = isFriend(provisioningServer, from.asBareJid());
                }
                catch (NoResponseException | XMPPErrorException | NotConnectedException | InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Could not determine if " + from + " is a friend.", e);
                    return null;
                }

                if (isFriend) {
                    return SubscribeAnswer.Approve;
                }
                else {
                    return SubscribeAnswer.Deny;
                }
            }
        });
    }

    public void setConfiguredProvisioningServer(Jid provisioningServer) {
        this.configuredProvisioningServer = Objects.requireNonNull(provisioningServer, "Provisioning server must not be null");
    }

    public Jid getConfiguredProvisioningServer()
                    throws NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException {
        if (configuredProvisioningServer == null) {
            configuredProvisioningServer = findProvisioningServerComponent();
        }
        return configuredProvisioningServer;
    }

    /**
     * Try to find a provisioning server component.
     * 
     * @return the XMPP address of the provisioning server component if one was found.
     * @throws NoResponseException
     * @throws XMPPErrorException
     * @throws NotConnectedException
     * @throws InterruptedException
     * @see <a href="http://xmpp.org/extensions/xep-0324.html#servercomponent">XEP-0324 § 3.1.2 Provisioning Server as a server component</a>
     */
    public DomainBareJid findProvisioningServerComponent() throws NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException {
        final XMPPConnection connection = connection();
        ServiceDiscoveryManager sdm = ServiceDiscoveryManager.getInstanceFor(connection);
        List<DiscoverInfo> discoverInfos = sdm.findServicesDiscoverInfo(Constants.IOT_PROVISIONING_NAMESPACE, true, true);
        if (discoverInfos.isEmpty()) {
            return null;
        }
        Jid jid = discoverInfos.get(0).getFrom();
        assert (jid.isDomainBareJid());
        return jid.asDomainBareJid();
    }

    public boolean isFriend(Jid provisioningServer, BareJid friendInQuestion) throws NoResponseException, XMPPErrorException, NotConnectedException, InterruptedException {
        IoTIsFriend iotIsFriend = new IoTIsFriend(friendInQuestion);
        iotIsFriend.setTo(provisioningServer);
        IoTIsFriendResponse response = connection().createPacketCollectorAndSend(iotIsFriend).nextResultOrThrow();
        assert (response.getJid().equals(friendInQuestion));
        return response.getIsFriendResult();
    }

    public void sendFriendshipRequest(Jid jid) throws NotConnectedException, InterruptedException {
        Presence presence = new Presence(Presence.Type.subscribe);
        presence.setTo(jid);
        connection().sendStanza(presence);
    }

    public void sendFriendshipRequestIfRequired(Jid jid) throws NotConnectedException, InterruptedException {
        RosterEntry entry = roster.getEntry(jid.asBareJid());
        if (entry != null && entry.canSeeHisPresence()) {
            return;
        }
        sendFriendshipRequest(jid);
    }

    public boolean isBefriended(Jid friendInQuestion) {
        return roster.isSubscribedToMyPresence(friendInQuestion);
    }

    private boolean isFromProvisioningService(Stanza stanza) {
        Jid provisioningServer;
        try {
            provisioningServer = getConfiguredProvisioningServer();
        }
        catch (NotConnectedException | InterruptedException | NoResponseException | XMPPErrorException e) {
            LOGGER.log(Level.WARNING, "Could determine provisioning server", e);
            return false;
        }
        if (provisioningServer == null) {
            LOGGER.warning("Ignoring request '" + stanza
                            + "' because no provisioning server configured.");
            return false;
        }
        if (!provisioningServer.equals(stanza.getFrom())) {
            LOGGER.warning("Ignoring  request '" + stanza + "' because not from provising server '"
                            + provisioningServer + "'.");
            return false;
        }
        return true;
    }
}
