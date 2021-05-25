# Maplestory v204.1 Fork of MapleEllinel

A Java Maplestory server emulator. This is a fork of the v203.4 repo [MapleEllinel](https://forum.ragezone.com/f427/mapleellinel-v203-4-based-swordie-1160913/) by Mechaviv and has since been worked on by Poki.

## Installation
- Join SwordieMS [Discord](https://discord.gg/qzjWZP7hc5).
- Proceed to server-setup-guide.
- Follow the steps accordingly to the steps given in the discord.
- Download, apply the following changes and build the given [AuthHook](https://github.com/pokiuwu/AuthHook-v203.4) in Microsoft Visual Studio.
  ```
  0x00C48930 -> auto StringPool__GetString = reinterpret_cast<StringPool__GetString_t>(0x00C48930);
  0x00820530 -> auto ZXString_char__Assign = reinterpret_cast<ZXString_char__Assign_t>(0x00820530);
  0x029E6290 -> ngs bypass
  0x029F44E0 -> crc bypass
  0x03706964 -> return *reinterpret_cast<CWvsContext**>(0x03706964);  
  ```
- Drag the output file from the build (ijl15.dll) into your v204.1 Maplestory directory and run a batch file with the following command `MapleStory.exe WebStart admin 8.31.99.141 8484`
- You should be good to go! :octocat:

## Client Installation


- Download 204.1 Client via [Depot Downloader](https://github.com/SteamRE/DepotDownloader).
 ```
  dotnet DepotDownloader.dll
  -app 216150 
  -depot 216151 
  -manifest 2563435375446666602 
  -username your_user
  -password your_pw
  ````
- Other link for clients 204.1:https://drive.google.com/file/d/1_MrradqBK_aIgwjPDSFzeWUBCghnE8xr/view
- For other manifests see [SteamDB](https://steamdb.info/depot/216151/manifests/)
- Setup guide [Google Document](https://docs.google.com/document/d/1BT0IEIUhEIrS9XWISzKcXiSY89PnACYBHnoNI7gIom8/edit?usp=sharing)

## Noteable Changes
- A decent amount of packets fixed (err 38)
- Threading issues
- Stability fixes, that the original v203.4 lacked

## Tech Stack
- [AdoptOpenJDK 16](https://adoptopenjdk.net/releases.html?variant=openjdk16&jvmVariant=hotspot)
- [MySQL](https://dev.mysql.com/downloads/workbench/) & [WAMP](https://www.wampserver.com/en/)
- Github (public version control)
- [IntelliJ](https://www.jetbrains.com/idea/)
- [WzDumper v1.9.2](https://github.com/Xterminatorz/WZ-Dumper/releases/tag/1.9.2)

## Credits
- Notable Credits: SwordieMS Team, Mechaviv
