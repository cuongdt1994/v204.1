package net.swordie.ms.handlers.user;

import net.swordie.ms.Server;
import net.swordie.ms.client.Account;
import net.swordie.ms.client.Client;
import net.swordie.ms.client.User;
import net.swordie.ms.client.character.Char;
import net.swordie.ms.client.character.HyperTPRock;
import net.swordie.ms.client.character.damage.DamageSkinType;
import net.swordie.ms.client.character.skills.MatrixInventory;
import net.swordie.ms.client.character.skills.TownPortal;
import net.swordie.ms.client.friend.result.LoadFriendResult;
import net.swordie.ms.client.jobs.Job;
import net.swordie.ms.client.jobs.JobManager;
import net.swordie.ms.client.party.Party;
import net.swordie.ms.connection.InPacket;
import net.swordie.ms.connection.crypto.TripleDESCipher;
import net.swordie.ms.connection.packet.*;
import net.swordie.ms.constants.GameConstants;
import net.swordie.ms.constants.JobConstants;
import net.swordie.ms.enums.DimensionalMirror;
import net.swordie.ms.enums.FieldOption;
import net.swordie.ms.enums.MapTransferType;
import net.swordie.ms.handlers.ClientSocket;
import net.swordie.ms.handlers.Handler;
import net.swordie.ms.handlers.header.InHeader;
import net.swordie.ms.life.npc.Npc;
import net.swordie.ms.loaders.NpcData;
import net.swordie.ms.scripts.ScriptManagerImpl;
import net.swordie.ms.scripts.ScriptType;
import net.swordie.ms.util.Util;
import net.swordie.ms.util.container.Tuple;
import net.swordie.ms.world.field.Field;
import net.swordie.ms.world.field.FieldInstanceType;
import net.swordie.ms.world.field.Portal;
import net.swordie.ms.world.shop.cashshop.CashShop;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MigrationHandler {

    private static final Logger log = Logger.getLogger(MigrationHandler.class);

    @Handler(op = InHeader.MIGRATE_IN)
    public static void handleMigrateIn(Client c, InPacket inPacket) {
        int worldId = inPacket.decodeInt();
        int charId = inPacket.decodeInt();
        byte[] machineID = inPacket.decodeArr(16);
        Tuple<Byte, Client> info = Server.getInstance().getChannelFromTransfer(charId, worldId);
        byte channel = info.getLeft();
        Client oldClient = info.getRight();
        if (!oldClient.hasCorrectMachineID(machineID)) {
            c.write(WvsContext.returnToTitle());
            return;
        }
        c.setMachineID(machineID);
        c.setOldChannel(oldClient.getOldChannel());
        User user = oldClient.getUser();
        c.setUser(user);
        Account acc = oldClient.getAccount();
        c.setAccount(acc);
        Server.getInstance().getWorldById(worldId).getChannelById(channel).removeClientFromTransfer(charId);
        c.setChannel(channel);
        c.setWorldId((byte) worldId);
        c.setChannelInstance(Server.getInstance().getWorldById(worldId).getChannelById(channel));
        Char chr = oldClient.getChr();
        if (chr == null || chr.getId() != charId) {
            chr = acc.getCharById(charId);
        }
        user.setCurrentChr(chr);
        user.setCurrentAcc(acc);
        chr.setUser(user);
        chr.setClient(c);
        chr.setAccount(acc);
        acc.setCurrentChr(chr);
        acc.setUser(user);
        chr.initEquips();
        chr.rebuildQuestExValues(false);
        chr.initAndroid(false);
        c.setChr(chr);
        c.getChannelInstance().addChar(chr);
        chr.setJobHandler(JobManager.getJobById(chr.getJob(), chr));
        chr.setFieldInstanceType(FieldInstanceType.CHANNEL);
        Server.getInstance().addUser(user);
        Field field = chr.getOrCreateFieldByCurrentInstanceType(chr.getFieldID() <= 0 ? 100000000 : chr.getFieldID());
        if (chr.getHP() <= 0) { // automatically revive when relogging
            chr.heal(chr.getMaxHP() / 2);
        }
        if (chr.getPartyID() != 0) {
            Party party = c.getWorld().getPartybyId(chr.getPartyID());
            if (party == null) {
                chr.setPartyID(0);
            } else {
                chr.setParty(party);
            }
        }
        // Init New Encryption
        byte[] aKey = new byte[] {0x4d, 0x40, 0x50, 0x6c, 0x65, 0x53, 0x74, 0x6f, 0x72, 0x79, 0x4d, 0x61, 0x50, 0x4c, 0x65, 0x21, 0x4d, 0x40, 0x50, 0x6c, 0x65, 0x53, 0x74, 0x6f};
        List<Integer> aUsed = new ArrayList<>();
        String sOpcode = "";
        for (int i = InHeader.B_E_G_I_N__U_S_E_R.getValue(); i < InHeader.NO.getValue(); i++) {
            int nNum = Util.getRandom(InHeader.B_E_G_I_N__U_S_E_R.getValue(), 9999);
            while (aUsed.contains(nNum)) {
                nNum = Util.getRandom(InHeader.B_E_G_I_N__U_S_E_R.getValue(), 9999);
            }
            String sNum = String.format("%04d", nNum);
            if (!aUsed.contains(nNum)) {
                c.mEncryptedOpcode.put(nNum, i);
                aUsed.add(nNum);
                sOpcode += sNum;
            }
        }
        aUsed.clear();

        TripleDESCipher pCipher = new TripleDESCipher(aKey);
        try {
            byte[] aBuffer = new byte[Short.MAX_VALUE + 1];
            byte[] aEncrypt = pCipher.Encrypt(sOpcode.getBytes());
            System.arraycopy(aEncrypt, 0, aBuffer, 0, aEncrypt.length);
            for (int i = aEncrypt.length; i < aBuffer.length; i++) {
                aBuffer[i] = (byte) Math.random();
            }
            c.write(Login.initOpcodeEncryption(4, aBuffer));
        } catch (Exception e) {
            e.printStackTrace();
        }
        // blessing has to be split up, as adding skills before SetField is send will crash the client
        c.write(WvsContext.updateEventNameTag(new int[]{11}));
        chr.initBlessingSkillNames();
        chr.warp(field, true);
        chr.initBlessingSkills();
        if (chr.getGuild() != null) {
            chr.setGuild(chr.getClient().getWorld().getGuildByID(chr.getGuild().getId()));
        }
        if (JobConstants.isBeastTamer(chr.getJob())) {
            c.write(FieldPacket.beastTamerFuncKeyMappedManInit());
        } else {
            c.write(FieldPacket.funcKeyMappedManInit(chr.getFuncKeyMap()));
        }
        chr.setBulletIDForAttack(chr.calculateBulletIDForAttack());
        c.write(WvsContext.friendResult(new LoadFriendResult(chr.getAllFriends())));
        c.write(WvsContext.macroSysDataInit(chr.getMacros()));
        c.write(UserLocal.damageSkinSaveResult(DamageSkinType.Req_SendInfo, null, chr));
        c.write(WvsContext.mapTransferResult(MapTransferType.RegisterListSend, (byte) 5, chr.getHyperRockFields()));
        acc.getMonsterCollection().init(chr);
        chr.checkAndRemoveExpiredItems();
        chr.initBaseStats();
        chr.setOnline(true); // v195+: respect 'invisible login' setting
        chr.getOffenseManager().setChr(chr);
        MatrixInventory.reloadSkills(chr);
        c.write(WvsContext.setMaplePoint(user.getMaplePoints()));
    }


    @Handler(op = InHeader.USER_TRANSFER_FIELD_REQUEST)
    public static void handleUserTransferFieldRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        if (inPacket.getUnreadAmount() == 0) {
            // Coming back from the cash shop
//            chr.warp(chr.getOrCreateFieldByCurrentInstanceType(chr.getFieldID()));
            c.getChannelInstance().addClientInTransfer(c.getChannel(), chr.getId(), c);
            c.write(ClientSocket.migrateCommand(true, (short) c.getChannelInstance().getPort()));
            return;
        }
        byte fieldKey = inPacket.decodeByte();
        int targetField = inPacket.decodeInt();
        int x = inPacket.decodeShort();
        int y = inPacket.decodeShort();
        String portalName = inPacket.decodeString();
        if (portalName != null && !"".equals(portalName)) {
            Field field = chr.getField();
            Portal portal = field.getPortalByName(portalName);
            if (portal.getScript() != null && !portal.getScript().equals("")) {
                chr.getScriptManager().startScript(portal.getId(), portal.getScript(), ScriptType.Portal);
            } else {
                Field toField = chr.getOrCreateFieldByCurrentInstanceType(portal.getTargetMapId());
                if (toField == null) {
                    return;
                }
                Portal toPortal = toField.getPortalByName(portal.getTargetPortalName());
                if (toPortal == null) {
                    toPortal = toField.getPortalByName("sp");
                }
                chr.warp(toField, toPortal);
            }
        } else if (chr.getHP() <= 0) {
            // Character is dead, respawn request
            inPacket.decodeByte(); // always 0
            byte tarfield = inPacket.decodeByte(); // ?
            byte reviveType = inPacket.decodeByte();
            int returnMap = chr.getField().getReturnMap();
            switch (reviveType) {
                // so far only got 0?
            }
            if (!chr.hasBuffProtector()) {
                chr.getTemporaryStatManager().removeAllStats();
            }
            int deathcount = chr.getDeathCount();
            if (deathcount != 0) {
                if (deathcount > 0) {
                    deathcount--;
                    chr.setDeathCount(deathcount);
                    chr.write(UserLocal.deathCountInfo(deathcount));
                }
                chr.warp(chr.getOrCreateFieldByCurrentInstanceType(returnMap));

            } else {
                if (chr.getInstance() != null) {
                    chr.getInstance().removeChar(chr);
                } else {
                    if (chr.getTransferField() == targetField && chr.getTransferFieldReq() == chr.getField().getId()) {
                        Field toField = chr.getOrCreateFieldByCurrentInstanceType(chr.getTransferField());
                        if (toField != null && chr.getTransferField() > 0) {
                            chr.warp(toField);
                        }
                        chr.setTransferField(0);
                        return;
                    } else {
                        chr.warp(chr.getOrCreateFieldByCurrentInstanceType(chr.getField().getForcedReturn()));
                    }
                }
            }
            chr.heal(chr.getMaxHP());
            chr.setBuffProtector(false);
        } else {
            if (chr.getTransferField() == targetField && chr.getTransferFieldReq() == chr.getField().getId()) {
                Field toField = chr.getOrCreateFieldByCurrentInstanceType(chr.getTransferField());
                if (toField != null && chr.getTransferField() > 0) {
                    chr.warp(toField);
                }
                chr.setTransferField(0);
            }
        }
    }

    @Handler(op = InHeader.USER_PORTAL_SCRIPT_REQUEST)
    public static void handleUserPortalScriptRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        byte portalID = inPacket.decodeByte();
        String portalName = inPacket.decodeString();
        Portal portal = chr.getField().getPortalByName(portalName);
        String script = portalName;
        if (portal != null) {
            portalID = (byte) portal.getId();
            script = "".equals(portal.getScript()) ? portalName : portal.getScript();
            chr.getScriptManager().startScript(portalID, script, ScriptType.Portal);
        } else {
            chr.chatMessage("Could not find that portal.");
        }
    }


    @Handler(op = InHeader.USER_TRANSFER_CHANNEL_REQUEST)
    public static void handleUserTransferChannelRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        byte channelId = (byte) (inPacket.decodeByte() + 1);
        if (c.getWorld().getChannelById(channelId) == null) {
            chr.chatMessage("Could not find that world.");
            return;
        }
        Field field = chr.getField();
        if ((field.getFieldLimit() & FieldOption.MigrateLimit.getVal()) > 0 ||
                channelId < 1 || channelId > c.getWorld().getChannels().size()) {
            chr.dispose();
            return;
        }
        Job sourceJobHandler = chr.getJobHandler();
        sourceJobHandler.handleCancelTimer(chr);
        chr.changeChannel(channelId);
    }

    @Handler(op = InHeader.USER_MIGRATE_TO_CASH_SHOP_REQUEST)
    public static void handleUserMigrateToCashShopRequest(Client c, InPacket inPacket) {
        Char chr = c.getChr();
        Field field = chr.getField();
        if ((field.getFieldLimit() & FieldOption.MigrateLimit.getVal()) > 0) {
            chr.dispose();
            return;
        }
        chr.punishLieDetectorEvasion();
        CashShop cs = Server.getInstance().getCashShop();
        c.write(Stage.setCashShop(chr, cs));
        c.write(CCashShop.loadLockerDone(chr.getAccount()));
        c.write(CCashShop.queryCashResult(chr));
        c.write(CCashShop.bannerInfo(cs));
        c.write(CCashShop.cartInfo(cs));
        c.write(CCashShop.featuredItemInfo(cs));
        c.write(CCashShop.specialItemInfo(cs));
        c.write(CCashShop.specialSaleInfo(cs));
        c.write(CCashShop.topSellerInfo(cs));
        c.write(CCashShop.categoryInfo(cs));
        c.write(CCashShop.bannerMsg(cs, new ArrayList<>(Arrays.asList("Welcome to SwordieMS!", "Enjoy your time here."))));
        c.write(CCashShop.oneTen(cs));
    }

    @Handler(op = InHeader.USER_MAP_TRANSFER_REQUEST)
    public static void handleUserMapTransferRequest(Char chr, InPacket inPacket) {
        chr.punishLieDetectorEvasion();

        byte mtType = inPacket.decodeByte();
        byte itemType = inPacket.decodeByte();

        MapTransferType mapTransferType = MapTransferType.getByVal(mtType);
        switch (mapTransferType) {
            case DeleteListRecv: //Delete request that's received
                int targetFieldID = inPacket.decodeInt();
                HyperTPRock.removeFieldId(chr, targetFieldID);
                chr.write(WvsContext.mapTransferResult(MapTransferType.DeleteListSend, itemType, chr.getHyperRockFields()));
                break;

            case RegisterListRecv: //Register request that's received
                targetFieldID = chr.getFieldID();
                Field field = chr.getField();
                if (field == null || (field.getFieldLimit() & FieldOption.TeleportItemLimit.getVal()) > 0) {
                    chr.chatMessage("You may not warp to that map.");
                    chr.dispose();
                    return;
                }
                HyperTPRock.addFieldId(chr, targetFieldID);
                chr.write(WvsContext.mapTransferResult(MapTransferType.RegisterListSend, itemType, chr.getHyperRockFields()));
                break;
        }
    }

    @Handler(op = InHeader.USER_FIELD_TRANSFER_REQUEST)
    public static void handleUserFieldTransferRequest(Char chr, InPacket inPacket) {
        Field field = chr.getField();
        if ((field.getFieldLimit() & FieldOption.TeleportItemLimit.getVal()) > 0
                || (field.getFieldLimit() & FieldOption.MigrateLimit.getVal()) > 0
                || (field.getFieldLimit() & FieldOption.PortalScrollLimit.getVal()) > 0
                || !field.isChannelField()) {
            chr.chatMessage("You may not warp to that map.");
            chr.dispose();
            return;
        }
        int fieldID = inPacket.decodeInt();
        if (fieldID == 7860) {
            Field ardentmill = chr.getOrCreateFieldByCurrentInstanceType(GameConstants.ARDENTMILL);
            chr.warp(ardentmill);
        }
    }

    @Handler(op = InHeader.MAKE_ENTER_FIELD_PACKET_FOR_QUICK_MOVE)
    public static void handleMakeEnterFieldPacketForQuickMove(Char chr, InPacket inPacket) {
        int templateID = inPacket.decodeInt();
        Npc npc = NpcData.getNpcDeepCopyById(templateID);
        String script = npc.getScripts().get(0);
        if (script == null) {
            script = String.valueOf(npc.getTemplateId());
        }
        chr.getScriptManager().startScript(npc.getTemplateId(), templateID, script, ScriptType.Npc);
    }

    @Handler(op = InHeader.ENTER_TOWN_PORTAL_REQUEST)
    public static void handleEnterTownPortalRequest(Char chr, InPacket inPacket) {
        int chrId = inPacket.decodeInt(); // Char id
        boolean town = inPacket.decodeByte() != 0;

        Field field = chr.getField();
        TownPortal townPortal = field.getTownPortalByChrId(chrId);
        if (townPortal != null) {       // TODO Using teleports, as grabbing the TownPortalPoint portal id is not working
            if (town) {
                // townField -> fieldField
                Field fieldField = townPortal.getChannel().getField(townPortal.getFieldFieldId());

                chr.warp(fieldField); // Back to the original Door
                chr.write(FieldPacket.teleport(townPortal.getFieldPosition(), chr)); // Teleports player to the position of the TownPortal
            } else {
                // fieldField -> townField
                Field returnField = townPortal.getChannel().getField(townPortal.getTownFieldId()); // Initialise the Town Map,

                chr.warp(returnField); // warp Char
                chr.write(FieldPacket.teleport(townPortal.getTownPosition(), chr));
                if (returnField.getTownPortalByChrId(chrId) == null) { // So that every re-enter into the TownField doesn't spawn another TownPortal
                    returnField.broadcastPacket(WvsContext.townPortal(townPortal)); // create the TownPortal
                    returnField.addTownPortal(townPortal);
                }
            }
        } else {
            chr.dispose();
            log.warn("Character {" + chrId + "} tried entering a Town Portal in field {" + field.getId() + "} which does not exist."); // Potential Hacking Log
        }
    }

    @Handler(op = InHeader.USER_TRANSFER_FREE_MARKET_REQUEST)
    public static void handleTransferFreeMarketRequest(Char chr, InPacket inPacket) {
        byte toChannelID = (byte) (inPacket.decodeByte() + 1);
        int fieldID = inPacket.decodeInt();
        if (chr.getWorld().getChannelById(toChannelID) != null  && GameConstants.isFreeMarketField(fieldID)
                && GameConstants.isFreeMarketField(chr.getField().getId())) {
            Field toField = chr.getClient().getChannelInstance().getField(fieldID);
            if (toField == null) {
                chr.dispose();
                return;
            }
            int currentChannelID = chr.getClient().getChannel();
            if (currentChannelID != toChannelID) {
                chr.changeChannelAndWarp(toChannelID, fieldID);
            } else {
                chr.warp(toField);
            }
        }

        inPacket.decodeInt(); // tick
    }

    @Handler(op = InHeader.UNITY_PORTAL_REQUEST)
    public static void handleUnityPortalRequest(Char chr, InPacket inPacket) {
        inPacket.decodeInt();// tick
        DimensionalMirror unityPortal = DimensionalMirror.getByID(inPacket.decodeInt());
        if (unityPortal == null) {
            chr.sendNoticeMsg(String.format("Unity portal not found."));
            return;
        }
        ScriptManagerImpl scriptManager = chr.getScriptManager();
        if (unityPortal.getReqLevel() > chr.getLevel()) {
            chr.sendNoticeMsg(String.format("You must be at least Lv. %d to access this content.", unityPortal.getReqLevel()));
            return;
        }
        int reqQuest = unityPortal.getReqQuest();
        if (reqQuest != 0) {
            if (!scriptManager.hasQuestCompleted(reqQuest)) {
                chr.sendNoticeMsg("You must first complete the prerequisite quest.");
                return;
            }
        }
        if (unityPortal.getMapId() <= 0) {
            chr.sendNoticeMsg(String.format("Unhandled unity portal [%s]", unityPortal.name()));
            return;
        }
        if (unityPortal.getQuestToSave() != 0) {
            chr.getScriptManager().createQuestWithQRValue(unityPortal.getQuestToSave(), Integer.toString(chr.getFieldID()));
        }
        chr.getScriptManager().warp(unityPortal.getMapId(), unityPortal.getPortal());
    }

    @Handler(op = InHeader.GOLLUX_OUT_REQUEST)
    public static void handleGolluxOutReqeust(Char chr, InPacket inPacket) {
        if(chr.getFieldID()/1000000 != 863) {
            return;
        }
        String script = "GolluxOutReqeust";
        int npcId = 9010000; //admin npc
        chr.getScriptManager().startScript(npcId, npcId, script, ScriptType.Npc);
    }

}
