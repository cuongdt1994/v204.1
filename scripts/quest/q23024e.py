# 23012 - Wild Hunter 2nd job advancement quest

sm.setSpeakerID(2151002) # Belle
if sm.sendAskYesNo("Would you like to advance to the next level?"):
    sm.completeQuest(parentID)
    sm.jobAdvance(3310)
    sm.sendSayOkay("Congratulations, you are now at the next level! I have given you some SP, enjoy!")
else:
    sm.sendSayOkay("Come back when you're ready.")
sm.dispose()
