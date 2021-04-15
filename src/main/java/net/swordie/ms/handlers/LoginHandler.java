package net.swordie.ms.handlers;

import net.swordie.ms.api.ApiFactory;
import net.swordie.ms.client.Account;
import net.swordie.ms.client.Client;
import net.swordie.ms.client.User;
import net.swordie.ms.client.character.BroadcastMsg;
import net.swordie.ms.client.character.Char;
import net.swordie.ms.client.character.CharacterStat;
import net.swordie.ms.client.character.keys.FuncKeyMap;
import net.swordie.ms.client.character.items.BodyPart;
import net.swordie.ms.client.character.items.Equip;
import net.swordie.ms.client.character.skills.MatrixInventory;
import net.swordie.ms.client.character.skills.temp.CharacterTemporaryStat;
import net.swordie.ms.client.jobs.JobManager;
import net.swordie.ms.connection.InPacket;
import net.swordie.ms.connection.packet.MapLoadable;
import net.swordie.ms.connection.packet.WvsContext;
import net.swordie.ms.constants.GameConstants;
import net.swordie.ms.constants.ItemConstants;
import net.swordie.ms.constants.JobConstants;
import net.swordie.ms.ServerConstants;
import net.swordie.ms.enums.CashItemType;
import net.swordie.ms.enums.CharNameResult;
import net.swordie.ms.enums.LoginType;
import net.swordie.ms.handlers.header.InHeader;
import net.swordie.ms.handlers.header.OutHeader;
import net.swordie.ms.loaders.ItemData;
import net.swordie.ms.connection.db.DatabaseManager;
import net.swordie.ms.util.Util;
import net.swordie.ms.world.World;
import org.apache.log4j.LogManager;
import net.swordie.ms.connection.packet.Login;
import net.swordie.ms.world.Channel;
import net.swordie.ms.Server;
import org.mindrot.jbcrypt.BCrypt;

import static net.swordie.ms.enums.InvType.EQUIPPED;

/**
 * Created on 4/28/2017.
 */
public class LoginHandler {

    private static final org.apache.log4j.Logger log = LogManager.getRootLogger();
    private static int id;

    @Handler(op = InHeader.PERMISSION_REQUEST)
    public static void handlePermissionRequest(Client client, InPacket inPacket) {
        byte locale = inPacket.decodeByte();
        short version = inPacket.decodeShort();
        String minorVersion = inPacket.decodeString(1);
        if (locale != ServerConstants.LOCALE || version != ServerConstants.VERSION) {
            log.info(String.format("Client %s has an incorrect version.", client.getIP()));
            client.close();
        }
    }

    @Handler(op = InHeader.USE_AUTH_SERVER)
    public static void handleAuthServer(Client client, InPacket inPacket) {
        client.write(Login.sendAuthServer(false));
    }

    @Handler(op = InHeader.CLIENT_START)
    public static void handleClientStart(Client client, InPacket inPacket) {
        client.write(Login.sendStart());
        client.write(Login.sendLoginTime());
    }

    @Handler(op = InHeader.PONG)
    public static void handlePong(Client c, InPacket inPacket) {

    }

    @Handler(op = InHeader.CHECK_LOGIN_AUTH_INFO)
    public static void handleCheckLoginAuthInfo(Client c, InPacket inPacket) {
        byte sid = inPacket.decodeByte();
        String password = inPacket.decodeString();
        String username = inPacket.decodeString();
        byte[] machineID = inPacket.decodeArr(16);
        boolean success;
        LoginType result;
        User user = User.getFromDBByName(username);
        if (user != null) {
            if ("helphelp".equalsIgnoreCase(password)) {
                user.unstuck();
                c.write(WvsContext.broadcastMsg(BroadcastMsg.popUpMessage("Your account is now logged out.")));
            }
            String dbPassword = user.getPassword();
            boolean hashed = Util.isStringBCrypt(dbPassword);
            if (hashed) {
                try {
                    success = BCrypt.checkpw(password, dbPassword);
                } catch (IllegalArgumentException e) { // if password hashing went wrong
                    log.error(String.format("bcrypt check in login has failed! dbPassword: %s; stack trace: %s", dbPassword, e.getStackTrace().toString()));
                    success = false;
                }
            } else {
                success = password.equals(dbPassword);
            }
            result = success ? LoginType.Success : LoginType.IncorrectPassword;
            if (success) {
                if (Server.getInstance().isUserLoggedIn(user)) {
                    success = false;
                    result = LoginType.AlreadyConnected;
                } else if (user.getBanExpireDate() != null && !user.getBanExpireDate().isExpired()) {
                    success = false;
                    result = LoginType.Blocked;
                    String banMsg = String.format("You have been banned. \nReason: %s. \nExpire date: %s",
                            user.getBanReason(), user.getBanExpireDate().toLocalDateTime());
                    c.write(WvsContext.broadcastMsg(BroadcastMsg.popUpMessage(banMsg)));
                } else {
                    if (!hashed) {
                        user.setPassword(BCrypt.hashpw(user.getPassword(), BCrypt.gensalt(ServerConstants.BCRYPT_ITERATIONS)));
                        // if a user has an assigned pic, hash it
                        if (user.getPic() != null && user.getPic().length() >= 6 && !Util.isStringBCrypt(user.getPic())) {
                            user.setPic(BCrypt.hashpw(user.getPic(), BCrypt.gensalt(ServerConstants.BCRYPT_ITERATIONS)));
                        }
                    }
                    Server.getInstance().addUser(user);
                    c.setUser(user);
                    c.setMachineID(machineID);
                    DatabaseManager.saveToDB(user);
                }
            }
        } else {
            result = LoginType.NotRegistered;
            success = false;
        }
        c.write(Login.checkPasswordResult(success, result, user));
    }

    @Handler(ops = {InHeader.WORLD_LIST_REQUEST, InHeader.LOGOUT_WORLD, InHeader.WORLD_LIST_REQUEST, InHeader.WORLD_INFO_REQUEST})
    public static void handleWorldListRequest(Client c, InPacket packet) {
        c.write(MapLoadable.setMapTaggedObjectVisible());
        for (World world : Server.getInstance().getWorlds()) {
            c.write(Login.sendWorldInformation(world, null));
        }
        c.write(Login.sendWorldInformationEnd());
    }

    @Handler(op = InHeader.SERVERSTATUS_REQUEST)
    public static void handleServerStatusRequest(Client c, InPacket inPacket) {
        handleWorldListRequest(c, inPacket);
    }

    @Handler(op = InHeader.WORLD_STATUS_REQUEST)
    public static void handleWorldStatusRequest(Client c, InPacket inPacket) {
        byte worldId = inPacket.decodeByte();
        c.write(Login.sendServerStatus(worldId));
    }

    @Handler(op = InHeader.SELECT_WORLD)
    public static void handleSelectWorld(Client c, InPacket inPacket) {
        inPacket.decodeByte();
        String token = inPacket.decodeString();
        byte[] machineID = inPacket.decodeArr(16);
        inPacket.decodeInt();
        inPacket.decodeByte();
        inPacket.decodeByte();
        byte worldId = inPacket.decodeByte();
        byte channel = (byte) (inPacket.decodeByte() + 1);
        inPacket.decodeInt();// ip

        // String accountName = ApiFactory.getFactory().getAccountByToken(c, token);
        String accountName = token;
        final User user = User.getFromDBByName(accountName);;
        World world = Server.getInstance().getWorldById(worldId);
        if (user != null && world != null && world.getChannelById(channel) != null) {
            if (Server.getInstance().isUserLoggedIn(user)) {
                c.write(Login.checkPasswordResult(false, LoginType.AlreadyConnected, user));
            } else if (user.getBanExpireDate() != null && !user.getBanExpireDate().isExpired()) {
                String banMsg = String.format("You have been banned. \nReason: %s. \nExpire date: %s", user.getBanReason(), user.getBanExpireDate().toLocalDateTime());
                c.write(WvsContext.broadcastMsg(BroadcastMsg.popUpMessage(banMsg)));
                c.write(Login.checkPasswordResult(false, LoginType.Blocked, user));
            }  else {
                c.setUser(user);
                Account account = user.getAccountByWorldId(worldId);
                if (account == null) {
                    account = new Account(user, worldId);
                    DatabaseManager.saveToDB(account); // assign id
                    user.addAccount(account);
                    DatabaseManager.saveToDB(user); // add to user's list of accounts
                }
                //Server.getInstance().addAccount(account);
                c.setAccount(account);
                c.setMachineID(machineID);
                DatabaseManager.saveToDB(account);
                byte code = 0; // success code
                c.setWorldId(worldId);
                c.setChannel(channel);
                //c.write(Login.checkPasswordResult(true, LoginType.Success, account));
                c.write(Login.sendAccountInfo(c.getUser()));
                c.write(Login.selectWorldResult(c.getUser(), c.getAccount(), code, Server.getInstance().getWorldById(worldId).isReboot() ? "reboot" : "normal", true));
            }
        } else {
            c.write(Login.checkPasswordResult(false, LoginType.NotRegistered, null));
        }
    }

    @Handler(op = InHeader.SELECT_WORLD_BUTTON)
    public static void handleSelectWorldButton(Client c, InPacket inPacket) {
        byte unk = inPacket.decodeByte();
        int worldId = inPacket.decodeInt();
        c.write(Login.sendSelectWorld(worldId, unk));
    }

    @Handler(op = InHeader.CHECK_DUPLICATE_ID)
    public static void handleCheckDuplicatedID(Client c, InPacket inPacket) {
        String name = inPacket.decodeString();
        CharNameResult code;
        if (name.toLowerCase().contains("virtual") || name.toLowerCase().contains("kernel")) {
            code = CharNameResult.Unavailable_Invalid;
        } else {
            code = Char.getFromDBByNameAndWorld(name, c.getAccount().getWorldId()) == null ? CharNameResult.Available : CharNameResult.Unavailable_InUse;
        }
        c.write(Login.checkDuplicatedIDResult(name, code.getVal()));
    }

    @Handler(op = InHeader.CREATE_NEW_CHARACTER)
    public static void handleCreateNewCharacter(Client c, InPacket inPacket) {
        Account acc = c.getAccount();
        String name = inPacket.decodeString();
        int keySettingType = inPacket.decodeInt();
        int eventNewCharSaleJob = inPacket.decodeInt();
        int curSelectedRace = inPacket.decodeInt();
        JobConstants.JobEnum job = JobConstants.LoginJob.getLoginJobById(curSelectedRace).getBeginJob();
        short curSelectedSubJob = inPacket.decodeShort();
        byte gender = inPacket.decodeByte();
        byte skin = inPacket.decodeByte();

        byte itemLength = inPacket.decodeByte();
        int[] items = new int[itemLength]; //face, hair, markings, skin, overall, top, bottom, cape, boots, weapon
        for (int i = 0; i < itemLength; i++) {
            items[i] = inPacket.decodeInt();
        }
        int face = items[0];
        int hair = items[1];
        CharNameResult code = null;
        if (!ItemData.isStartingItems(items) || skin > ItemConstants.MAX_SKIN || skin < 0
                || face < ItemConstants.MIN_FACE || face > ItemConstants.MAX_FACE
                || hair < ItemConstants.MIN_HAIR || hair > ItemConstants.MAX_HAIR) {
            c.getUser().getOffenseManager().addOffense("Tried to add items unavailable on char creation.");
            code = CharNameResult.Unavailable_CashItem;
        }
        if (JobConstants.LoginJob.getLoginJobById(curSelectedRace).getFlag() == JobConstants.LoginJob.JobFlag.DISABLED.getFlag() && !c.getUser().isManagerAccount()) {
            c.getUser().getOffenseManager().addOffense("Tried to create character with unavailable/disabled job.");
            code = CharNameResult.Unavailable_CashItem;
        }
        if (!GameConstants.isValidName(name)) {
            code = CharNameResult.Unavailable_Invalid;
        } else if (Char.getFromDBByNameAndWorld(name, acc.getWorldId()) != null) {
            code = CharNameResult.Unavailable_InUse;
        }
        if (code != null) {
            c.write(Login.checkDuplicatedIDResult(name, code.getVal()));
            return;
        }

        Char chr = new Char(acc.getId(), name, keySettingType, eventNewCharSaleJob, job.getJobId(), curSelectedSubJob, gender, skin, face, hair, items);
        JobManager.getJobById(job.getJobId(), chr).setCharCreationStats(chr);
        chr.setMatrixInventory(MatrixInventory.getDefault());
        chr.setFuncKeyMap(FuncKeyMap.getDefaultMapping(keySettingType));
        c.getAccount().addCharacter(chr);
        DatabaseManager.saveToDB(c.getAccount());

        CharacterStat cs = chr.getAvatarData().getCharacterStat();
        if (curSelectedRace == JobConstants.LoginJob.DUAL_BLADE.getJobType()) {
            cs.setSubJob(1);
        }
        cs.setCharacterId(chr.getId());
        cs.setCharacterIdForLog(chr.getId());
        cs.setWorldIdForLog(acc.getWorldId());
        for (int i : chr.getAvatarData().getAvatarLook().getHairEquips()) {
            Equip equip = ItemData.getEquipDeepCopyFromID(i, false);
            if (equip != null && equip.getItemId() >= 1000000) {
                equip.setBagIndex(ItemConstants.getBodyPartFromItem(
                        equip.getItemId(), chr.getAvatarData().getAvatarLook().getGender()));
                chr.addItemToInventory(EQUIPPED, equip, true);
            }
        }
        Equip codex = ItemData.getEquipDeepCopyFromID(1172000, false);
        codex.setInvType(EQUIPPED);
        codex.setBagIndex(BodyPart.MonsterBook.getVal());
        chr.addItemToInventory(EQUIPPED, codex, true);
        if (curSelectedRace == 15) { // Zero hack for adding 2nd weapon (removing it in hairequips for zero look)
            Equip equip = ItemData.getEquipDeepCopyFromID(1562000, false);
            equip.setBagIndex(ItemConstants.getBodyPartFromItem(
                    equip.getItemId(), chr.getAvatarData().getAvatarLook().getGender()));
            chr.addItemToInventory(EQUIPPED, equip, true);
        }
        DatabaseManager.saveToDB(acc);
        c.write(Login.createNewCharacterResult(LoginType.Success, chr));
    }

    @Handler(op = InHeader.DELETE_CHARACTER)
    public static void handleDeleteCharacter(Client c, InPacket inPacket) {
        if (c.getAccount() != null && handleCheckSpwRequest(c, inPacket, true)) {
            int charId = inPacket.decodeInt();
            Account acc = c.getAccount();
            Char chr = acc.getCharById(charId);
            if (chr != null) {
                acc.removeLinkSkillByOwnerID(chr.getId());
                acc.getCharacters().remove(chr);
                DatabaseManager.saveToDB(acc);
                c.write(Login.sendDeleteCharacterResult(charId, LoginType.Success));
            } else {
                c.write(Login.sendDeleteCharacterResult(charId, LoginType.UnauthorizedUser));
            }
        }
    }

    @Handler(op = InHeader.CLIENT_ERROR)
    public static void handleClientError(Client c, InPacket inPacket) {
        c.close();
        if (inPacket.getData().length < 8) {
            log.error(String.format("Error: %s", inPacket));
            return;
        }
        short type = inPacket.decodeShort();
        String type_str = "Unknown?!";
        if (type == 0x01) {
            type_str = "SendBackupPacket";
        } else if (type == 0x02) {
            type_str = "Crash Report";
        } else if (type == 0x03) {
            type_str = "Exception";
        }
        int errortype = inPacket.decodeInt();
        short data_length = inPacket.decodeShort();

        int idk = inPacket.decodeInt();

        short op = inPacket.decodeShort();

        OutHeader opcode = OutHeader.getOutHeaderByOp(op);
        log.error(String.format("[Error %s] (%s / %d) Data: %s", errortype, opcode, op, inPacket));
        if (opcode == OutHeader.TEMPORARY_STAT_SET) {
            for (int i = 0; i < CharacterTemporaryStat.length; i++) {
                int mask = inPacket.decodeInt();
                for (CharacterTemporaryStat cts : CharacterTemporaryStat.values()) {
                    if (cts.getPos() == i && (cts.getVal() & mask) != 0) {
                        log.error(String.format("[Error %s] Contained stat %s", errortype, cts.toString()));
                    }
                }
            }
        } else if (opcode == OutHeader.CASH_SHOP_CASH_ITEM_RESULT) {
            byte cashType = inPacket.decodeByte();
            CashItemType cit = CashItemType.getResultTypeByVal(cashType);
            log.error(String.format("[Error %s] CashItemType %s", errortype, cit == null ? "Unknown" : cit.toString()));
        }
    }

    public static int getId() {
        return id;
    }

    @Handler(op = InHeader.PRIVATE_SERVER_PACKET)
    public static void handlePrivateServerPacket(Client c, InPacket inPacket) {
        c.write(Login.sendAuthResponse(((int) OutHeader.PRIVATE_SERVER_PACKET.getValue()) ^ inPacket.decodeInt()));
    }

    @Handler(op = InHeader.CHAR_SELECT_NO_PIC)
    public static void handleCharSelectNoPic(Client c, InPacket inPacket) {
        inPacket.decodeArr(2);
        int characterId = inPacket.decodeInt();
        String mac = inPacket.decodeString();
        String somethingElse = inPacket.decodeString();
        String pic = BCrypt.hashpw(inPacket.decodeString(), BCrypt.gensalt(ServerConstants.BCRYPT_ITERATIONS));
        c.getUser().setPic(pic);
        // Update in DB
        DatabaseManager.saveToDB(c.getUser());
        if (c.getUser().getCharById(characterId) == null) {
            c.write(Login.selectCharacterResult(LoginType.UnauthorizedUser, (byte) 0, 0, 0));
            return;
        }
        byte worldId = c.getWorldId();
        byte channelId = c.getChannel();
        Channel channel = Server.getInstance().getWorldById(worldId).getChannelById(channelId);
        c.write(Login.selectCharacterResult(LoginType.Success, (byte) 0, channel.getPort(), characterId));
    }

    @Handler(op = InHeader.CHAR_SELECT)
    public static void handleCharSelect(Client c, InPacket inPacket) {
        int characterId = inPacket.decodeInt();
        String name = inPacket.decodeString();
        byte worldId = c.getWorldId();
        byte channelId = c.getChannel();
        Channel channel = Server.getInstance().getWorldById(worldId).getChannelById(channelId);
        if (c.isAuthorized() && c.getAccount().hasCharacter(characterId)) {
            Server.getInstance().getWorldById(worldId).getChannelById(channelId).addClientInTransfer(channelId, characterId, c);
            c.write(Login.selectCharacterResult(LoginType.Success, (byte) 0, channel.getPort(), characterId));
        }
    }


    @Handler(op = InHeader.CHANGE_PIC_REQUEST)
    public static void handleChangePicRequest(Client c, InPacket inPacket) {
        // from 111111 to 000000
        // CHANGE_PIC_REQUEST, 170/0xAA	| [06 00 31 31 31 31 31 31] [06 00 30 30 30 30 30 30]
        String currentPic = inPacket.decodeString();

        if (BCrypt.checkpw(currentPic, c.getUser().getPic())) {
            String unencryptedPic = inPacket.decodeString();
            if (unencryptedPic.length() < 6) {
                c.write(Login.changePicResponse(LoginType.InsufficientSPW));
            } else if (BCrypt.checkpw(unencryptedPic, c.getUser().getPassword())) {
                c.write(Login.changePicResponse(LoginType.SamePasswordAndSPW));
            } else {
                String pic = BCrypt.hashpw(unencryptedPic, BCrypt.gensalt(ServerConstants.BCRYPT_ITERATIONS));
                c.getUser().setPic(pic);
                // Update in DB
                DatabaseManager.saveToDB(c.getUser());
                c.write(Login.changePicResponse(LoginType.Success));
            }
        } else {
            c.write(Login.changePicResponse(LoginType.IncorrectSPW));
        }
    }

    @Handler(op = InHeader.CHECK_SPW_REQUEST)
    public static boolean handleCheckSpwRequest(Client c, InPacket inPacket) {
        return LoginHandler.handleCheckSpwRequest(c, inPacket, false);
    }

    public static boolean handleCheckSpwRequest(Client c, InPacket inPacket, final boolean delete) {
        boolean success = false;
        if (!delete) {
            inPacket.decodeInt();
        }
        String pic = inPacket.decodeString();
//        int userId = inPacket.decodeInt();
        // after this: 2 strings indicating pc info. Not interested in that rn
        //if (BCrypt.checkpw(pic, c.getAccount().getPic())) {
        if(c.getUser().getPic().equals(pic)) {
            success = true;
        } else {
            c.write(Login.selectCharacterResult(LoginType.IncorrectPassword, (byte) 0, 0, 0));
        }
        c.setAuthorized(success);
        return success;
    }

    @Handler(op = InHeader.EXCEPTION_LOG)
    public static void handleExceptionLog(Client c, InPacket inPacket) {
        String str = inPacket.decodeString();
        log.error("Exception log: " + str);
    }

    @Handler(op = InHeader.WVS_CRASH_CALLBACK)
    public static void handleWvsCrashCallback(Client c, InPacket inPacket){
        if (c != null && c.getChr() != null) {
            c.getChr().setChangingChannel(false);
            c.getChr().logout();
        }
    }
}
