package net.swordie.ms.handlers.social;

import net.swordie.ms.Server;
import net.swordie.ms.client.Client;
import net.swordie.ms.client.character.Char;
import net.swordie.ms.client.character.commands.AdminCommand;
import net.swordie.ms.client.character.commands.AdminCommands;
import net.swordie.ms.client.character.commands.Command;
import net.swordie.ms.connection.InPacket;
import net.swordie.ms.connection.db.DatabaseManager;
import net.swordie.ms.connection.packet.ChatSocket;
import net.swordie.ms.connection.packet.FieldPacket;
import net.swordie.ms.connection.packet.UserPacket;
import net.swordie.ms.enums.BaseStat;
import net.swordie.ms.enums.ChatUserType;
import net.swordie.ms.enums.GroupMessageType;
import net.swordie.ms.enums.PrivateStatusIDFlag;
import net.swordie.ms.handlers.Handler;
import net.swordie.ms.handlers.header.InHeader;
import net.swordie.ms.loaders.StringData;
import net.swordie.ms.scripts.ScriptManagerImpl;
import net.swordie.ms.scripts.ScriptType;
import net.swordie.ms.world.World;
import org.apache.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.swordie.ms.enums.ChatType.*;

public class ChatHandler {

    private static final Logger log = Logger.getLogger(ChatHandler.class);

    @Handler(op = InHeader.USER_CHAT)
    public static void handleUserChat(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        if (chr == null) {
            return;
        }
        inPacket.decodeInt();
        String msg = inPacket.decodeString();
        boolean append = inPacket.decodeByte() == 1;

        if (msg.length() > 0 && msg.charAt(0) == '@') {
            if (msg.equalsIgnoreCase("@check")) {
                chr.dispose();
                Map<BaseStat, Integer> basicStats = chr.getTotalBasicStats();
                StringBuilder sb = new StringBuilder();
                List<BaseStat> sortedList = Arrays.stream(BaseStat.values()).sorted(Comparator.comparing(Enum::toString)).collect(Collectors.toList());
                for (BaseStat bs : sortedList) {
                    sb.append(String.format("%s = %d, ", bs, basicStats.getOrDefault(bs, 0)));
                }
                chr.chatMessage(Mob, String.format("X=%d, Y=%d %n Stats: %s", chr.getPosition().getX(), chr.getPosition().getY(), sb));
                ScriptManagerImpl smi = chr.getScriptManager();
                // all but field
                smi.stop(ScriptType.Portal);
                smi.stop(ScriptType.Npc);
                smi.stop(ScriptType.Reactor);
                smi.stop(ScriptType.Quest);
                smi.stop(ScriptType.Item);

            } else if (msg.equalsIgnoreCase("@save")) {
                DatabaseManager.saveToDB(chr);
            }
        } else if (msg.charAt(0) == AdminCommand.getPrefix() && chr.getUser().getPrivateStatusIDFlag().ordinal() > PrivateStatusIDFlag.NONE.ordinal()) {
            boolean executed = false;
            String command = msg.split(" ")[0].replace("!", "");
            for (Class clazz : AdminCommands.class.getClasses()) {
                Command cmd = (Command) clazz.getAnnotation(Command.class);
                boolean matchingCommand = false;
                for (String name : cmd.names()) {
                    if (name.equalsIgnoreCase(command) && chr.getUser().getPrivateStatusIDFlag().hasFlag(cmd.requiredType())) {
                        matchingCommand = true;
                        break;
                    }
                }
                if (matchingCommand) {
                    executed = true;
                    String[] split = null;
                    try {
                        AdminCommand adminCommand = (AdminCommand) clazz.getConstructor().newInstance();
                        Method method = clazz.getDeclaredMethod("execute", Char.class, String[].class);
                        split = msg.split(" ");
                        method.invoke(adminCommand, c.getChr(), split);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                            | InstantiationException e) {
                        e.printStackTrace();
                    }
                }
            }
            if (!executed) {
                chr.chatMessage(Expedition, "Unknown command \"" + command + "\"");
            }
        } else {
            chr.getField().broadcastPacket(UserPacket.chat(chr.getId(), chr.getName(), ChatUserType.User, msg, append, 0, c.getWorldId()));
        }
    }

    @Handler(op = InHeader.WHISPER)
    public static void handleWhisper(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        byte type = inPacket.decodeByte();
        inPacket.decodeInt(); // tick
        String destName = inPacket.decodeString();
        Char dest = c.getWorld().getCharByName(destName);
        if (dest == null) {
            c.write(FieldPacket.whisper(chr, destName, (byte) 0, false, "", true));
            return;
        }
        switch (type) {
            case 5: // /find command
                int fieldId = dest.getField().getId();
                int channel = dest.getClient().getChannel();
                if (channel != chr.getClient().getChannel()) {
                    chr.chatMessage("%s is in channel %s-%d.", dest.getName(), dest.getWorld().getName(), channel);
                } else {
                    String fieldString = StringData.getMapStringById(fieldId);
                    if (fieldString == null) {
                        fieldString = "Unknown field.";
                    }
                    chr.chatMessage("%s is at %s.", dest.getName(), fieldString);
                }
                break;
            case 68:
                break;
            case 6: // whisper
                String msg = inPacket.decodeString();
                dest.write(FieldPacket.whisper(chr, chr.getName(), (byte) (c.getChannel() - 1), false, msg, false));
                chr.chatMessage(Whisper, String.format("%s<< %s", dest.getName(), msg));
                break;
        }

    }

    @Handler(op = InHeader.GROUP_MESSAGE)
    public static void handleGroupMessage(Char chr, InPacket inPacket) {
        byte type = inPacket.decodeByte(); // party = 1, alliance = 3
        byte idk2 = inPacket.decodeByte();
        int idk3 = inPacket.decodeInt(); // party id?
        String msg = inPacket.decodeString();
        if (type == 1 && chr.getParty() != null) {
            chr.getParty().broadcast(FieldPacket.groupMessage(GroupMessageType.Party, chr, msg), chr);
        } else if (type == 3 && chr.getGuild() != null && chr.getGuild().getAlliance() != null) {
            chr.getGuild().getAlliance().broadcast(FieldPacket.groupMessage(GroupMessageType.Alliance, chr, msg), chr);
        }
    }

    @Handler(op = InHeader.CONNECT_CHAT)
    public static void handleConnect(Client c, InPacket inPacket) {
        int accID = inPacket.decodeInt();
        int idk = inPacket.decodeInt(); // always 1?
        long idk2 = inPacket.decodeLong();
        boolean idk3 = inPacket.decodeByte() != 0;
        int charID = inPacket.decodeInt();
        String charName = inPacket.decodeString();
        int level = inPacket.decodeInt();
        int job = inPacket.decodeInt();
        Char chr = null;
        for (World w : Server.getInstance().getWorlds()) {
            chr = w.getCharByID(charID);
            if (chr != null) {
                break;
            }
        }
        if (chr != null) {
            chr.setChatClient(c);
            c.setChr(chr);
            chr.getWorld().getConnectedChatClients().put(accID, c);
        }
        c.write(ChatSocket.loginResult(chr != null));
    }

    @Handler(op = InHeader.FRIEND_CHAT)
    public static void handleFriendChat(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int accID = inPacket.decodeInt();
        String msg = inPacket.decodeString();
        int size = inPacket.decodeInt();
        for (int i = 0; i < size; i++) {
            if (chr.getWorld().getConnectedChatClients().containsKey(i)) {
                chr.getWorld().getConnectedChatClients().get(i).write(ChatSocket.friendChatMessage(accID, chr.getId(), null, msg, false));
            }
        }
    }

    @Handler(op = InHeader.GUILD_CHAT)
    public static void handleGuildChat(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        int charID = inPacket.decodeInt();
        int guildID = inPacket.decodeInt();
        String msg = inPacket.decodeString();
        if (chr.getGuild() != null) {
            chr.getGuild().broadcast(FieldPacket.groupMessage(GroupMessageType.Guild, chr, msg));
        }
    }

}
