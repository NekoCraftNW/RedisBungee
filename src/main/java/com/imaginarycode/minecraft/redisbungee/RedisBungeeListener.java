/**
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <http://unlicense.org/>
 */
package com.imaginarycode.minecraft.redisbungee;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.util.RedisCallable;
import lombok.AllArgsConstructor;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.Callable;

@AllArgsConstructor
public class RedisBungeeListener implements Listener {
    private static final BaseComponent[] ALREADY_LOGGED_IN =
            new ComponentBuilder("You are already logged on to this server.").color(ChatColor.RED)
                    .append("\n\nIf you were disconnected forcefully, please wait up to one minute.\nIf this does not resolve your issue, please contact staff.")
                    .color(ChatColor.GRAY)
                    .create();
    private final RedisBungee plugin;
    private final List<InetAddress> exemptAddresses;

    @EventHandler
    public void onPlayerConnect(final PostLoginEvent event) {
        try (Jedis rsc = plugin.getPool().getResource()) {
            for (String server : plugin.getServerIds()) {
                if (rsc.sismember("proxy:" + server + ":usersOnline", event.getPlayer().getUniqueId().toString())) {
                    event.getPlayer().disconnect(ALREADY_LOGGED_IN);
                    return;
                }
            }
        }

        plugin.getService().submit(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                Pipeline pipeline = jedis.pipelined();
                pipeline.sadd("proxy:" + RedisBungee.getApi().getServerId() + ":usersOnline", event.getPlayer().getUniqueId().toString());
                pipeline.hset("player:" + event.getPlayer().getUniqueId().toString(), "online", "0");
                pipeline.hset("player:" + event.getPlayer().getUniqueId().toString(), "ip", event.getPlayer().getAddress().getAddress().getHostAddress());
                plugin.getUuidTranslator().persistInfo(event.getPlayer().getName(), event.getPlayer().getUniqueId(), pipeline);
                pipeline.hset("player:" + event.getPlayer().getUniqueId().toString(), "proxy", RedisBungee.getConfiguration().getServerId());
                pipeline.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getUniqueId(), DataManager.DataManagerMessage.Action.JOIN,
                        new DataManager.LoginPayload(event.getPlayer().getAddress().getAddress()))));
                pipeline.sync();
                return null;
            }
        });
    }

    @EventHandler
    public void onPlayerDisconnect(final PlayerDisconnectEvent event) {
        plugin.getService().submit(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                Pipeline pipeline = jedis.pipelined();
                long timestamp = System.currentTimeMillis();
                pipeline.hset("player:" + event.getPlayer().getUniqueId().toString(), "online", String.valueOf(timestamp));
                RedisUtil.cleanUpPlayer(event.getPlayer().getUniqueId().toString(), pipeline);
                pipeline.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getUniqueId(), DataManager.DataManagerMessage.Action.LEAVE,
                        new DataManager.LogoutPayload(timestamp))));
                pipeline.sync();
                return null;
            }
        });
    }

    @EventHandler
    public void onServerChange(final ServerConnectedEvent event) {
        plugin.getService().submit(new RedisCallable<Void>(plugin) {
            @Override
            protected Void call(Jedis jedis) {
                Pipeline pipeline = jedis.pipelined();
                pipeline.hset("player:" + event.getPlayer().getUniqueId().toString(), "server", event.getServer().getInfo().getName());
                pipeline.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                        event.getPlayer().getUniqueId(), DataManager.DataManagerMessage.Action.SERVER_CHANGE,
                        new DataManager.ServerChangePayload(event.getServer().getInfo().getName()))));
                pipeline.sync();
                return null;
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPing(final ProxyPingEvent event) {
        if (exemptAddresses.contains(event.getConnection().getAddress().getAddress())) {
            return;
        }

        event.registerIntent(plugin);
        plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
            @Override
            public void run() {
                ServerPing old = event.getResponse();
                ServerPing reply = new ServerPing();
                reply.setPlayers(new ServerPing.Players(old.getPlayers().getMax(), plugin.getCount(), old.getPlayers().getSample()));
                reply.setDescription(old.getDescription());
                reply.setFavicon(old.getFaviconObject());
                reply.setVersion(old.getVersion());
                event.setResponse(reply);
                event.completeIntent(plugin);
            }
        });
    }

    @EventHandler
    public void onPluginMessage(final PluginMessageEvent event) {
        if (event.getTag().equals("RedisBungee") && event.getSender() instanceof Server) {
            final byte[] data = Arrays.copyOf(event.getData(), event.getData().length);
            plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
                @Override
                public void run() {
                    ByteArrayDataInput in = ByteStreams.newDataInput(data);

                    String subchannel = in.readUTF();
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    String type;

                    switch (subchannel) {
                        case "PlayerList":
                            out.writeUTF("PlayerList");
                            Set<UUID> original = Collections.emptySet();
                            type = in.readUTF();
                            if (type.equals("ALL")) {
                                out.writeUTF("ALL");
                                original = plugin.getPlayers();
                            } else {
                                try {
                                    original = plugin.getPlayersOnServer(type);
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                            Set<String> players = new HashSet<>();
                            for (UUID uuid : original)
                                players.add(plugin.getUuidTranslator().getNameFromUuid(uuid, false));
                            out.writeUTF(Joiner.on(',').join(players));
                            break;
                        case "PlayerCount":
                            out.writeUTF("PlayerCount");
                            type = in.readUTF();
                            if (type.equals("ALL")) {
                                out.writeUTF("ALL");
                                out.writeInt(plugin.getCount());
                            } else {
                                out.writeUTF(type);
                                try {
                                    out.writeInt(plugin.getPlayersOnServer(type).size());
                                } catch (IllegalArgumentException e) {
                                    out.writeInt(0);
                                }
                            }
                            break;
                        case "LastOnline":
                            String user = in.readUTF();
                            out.writeUTF("LastOnline");
                            out.writeUTF(user);
                            out.writeLong(RedisBungee.getApi().getLastOnline(plugin.getUuidTranslator().getTranslatedUuid(user, true)));
                            break;
                        case "ServerPlayers":
                            String type1 = in.readUTF();
                            out.writeUTF("ServerPlayers");
                            Multimap<String, UUID> multimap = RedisBungee.getApi().getServerToPlayers();

                            boolean includesUsers;

                            switch (type1) {
                                case "COUNT":
                                    includesUsers = false;
                                    break;
                                case "PLAYERS":
                                    includesUsers = true;
                                    break;
                                default:
                                    // TODO: Should I raise an error?
                                    return;
                            }

                            out.writeUTF(type1);

                            if (includesUsers) {
                                Multimap<String, String> human = HashMultimap.create();
                                for (Map.Entry<String, UUID> entry : multimap.entries()) {
                                    human.put(entry.getKey(), plugin.getUuidTranslator().getNameFromUuid(entry.getValue(), false));
                                }
                                serializeMultimap(human, true, out);
                            } else {
                                // Due to Java generics, we are forced to coerce UUIDs into strings. This is less
                                // expensive than looking up names, since we just want counts.
                                Multimap<String, String> flunk = HashMultimap.create();
                                for (Map.Entry<String, UUID> entry : multimap.entries()) {
                                    flunk.put(entry.getKey(), entry.getValue().toString());
                                }
                                serializeMultimap(flunk, false, out);
                            }
                            break;
                        case "Proxy":
                            out.writeUTF("Proxy");
                            out.writeUTF(RedisBungee.getConfiguration().getServerId());
                            break;
                        default:
                            return;
                    }

                    ((Server) event.getSender()).sendData("RedisBungee", out.toByteArray());
                }
            });
        }
    }

    private void serializeMultimap(Multimap<String, String> collection, boolean includeNames, ByteArrayDataOutput output) {
        output.writeInt(collection.size());
        for (Map.Entry<String, Collection<String>> entry : collection.asMap().entrySet()) {
            output.writeUTF(entry.getKey());
            if (includeNames) {
                serializeCollection(entry.getValue(), output);
            } else {
                output.writeInt(entry.getValue().size());
            }
        }
    }

    private void serializeCollection(Collection<?> collection, ByteArrayDataOutput output) {
        output.writeInt(collection.size());
        for (Object o : collection) {
            output.writeUTF(o.toString());
        }
    }

    @EventHandler
    public void onPubSubMessage(PubSubMessageEvent event) {
        if (event.getChannel().equals("redisbungee-allservers") || event.getChannel().equals("redisbungee-" + RedisBungee.getApi().getServerId())) {
            String message = event.getMessage();
            if (message.startsWith("/"))
                message = message.substring(1);
            plugin.getLogger().info("Invoking command via PubSub: /" + message);
            plugin.getProxy().getPluginManager().dispatchCommand(RedisBungeeCommandSender.instance, message);
        }
    }
}
