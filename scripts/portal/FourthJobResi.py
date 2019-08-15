# Portal to the 4th Job Advancement for Main Resistance

validJobIDs = {
    3211 : 931000300,
    3311 : 931000301,
    3511 : 931000302,
    3711 : 931000303,
}

charJobID = sm.getChr().getJob()
if charJobID not in validJobIDs:
    sm.dispose()

sm.warpInstanceIn(validJobIDs[charJobID], 0, False)
sm.setInstanceTime(15*60)