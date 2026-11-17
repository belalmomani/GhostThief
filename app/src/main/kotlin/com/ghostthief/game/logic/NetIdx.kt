package com.ghostthief.game.logic

/** فهارس مصفوفات لقطة الحالة (Snapshot) المرسلة من مضيف اللعب إلى الهاتف الآخر. الإحداثيات تُرسل كنسب (0..1) من الساحة. */
object NetIdx {
    // مصفوفة الأعداد الكسرية
    const val F_THIEF_X = 0; const val F_THIEF_Y = 1; const val F_THIEF_STATE_TIME = 2; const val F_THIEF_ALPHA = 3
    const val F_THIEF_SCALE = 4; const val F_HIDE_FOLDER_ALPHA = 5; const val F_HIDE_X = 6; const val F_HIDE_Y = 7
    const val F_RUN_CLOCK = 8; const val F_IN_X = 9; const val F_IN_Y = 10
    const val F_OFF_X = 11; const val F_OFF_Y = 12; const val F_OFF_SCALE = 13; const val F_WALK_CLOCK = 14
    const val F_SPEED_RATIO = 15; const val F_DIR_X = 16; const val F_DIR_Y = 17
    const val F_CAR_X = 18; const val F_CAR_Y = 19; const val F_LIGHTS_CLOCK = 20; const val F_CAR_SPEED = 21
    const val F_STAGE_TIME = 22; const val F_SQ_THIEF_X = 23; const val F_SQ_THIEF_Y = 24; const val F_SQ_THIEF_SCALE = 25
    const val F_JAIL_FX = 26; const val F_JAIL_FY = 27; const val F_JAIL_PICK_TIME = 28; const val F_JAIL_CLOCK = 29
    const val F_HIDE_COOLDOWN = 30
    const val F_COUNT = 31

    // مصفوفة الأعداد الصحيحة
    const val I_THIEF_STATE = 0; const val I_THIEF_FACING = 1; const val I_HIDE_VARIANT = 2
    const val I_OFF_FACING = 3; const val I_OFF_VISIBLE = 4; const val I_OFF_MOVING = 5
    const val I_CAR_FACING = 6; const val I_CAR_VISIBLE = 7; const val I_STAGE = 8
    const val I_SQ_THIEF_VISIBLE = 9; const val I_OFFICER_BOARDS = 10
    const val I_JAIL_VISIBLE = 11; const val I_JAIL_PICK = 12
    const val I_SCORE_POLICE = 13; const val I_SCORE_THIEF = 14
    const val I_POLICE_ACTIVE = 15; const val I_TWO_PLAYER = 16
    const val I_HIDE_READY = 17; const val I_JAIL_REMAINING_MS = 18; const val I_JAIL_ACTIVE = 19
    const val I_COUNT = 20
}
