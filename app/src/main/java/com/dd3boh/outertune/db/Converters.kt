package com.dd3boh.outertune.db

import androidx.room.TypeConverter
import com.dd3boh.outertune.transition.model.EffectMode
import com.dd3boh.outertune.transition.model.EqMode
import com.dd3boh.outertune.transition.model.OverlapMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): LocalDateTime? =
        if (value != null) LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC)
        else null

    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): Long? =
        date?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    // Transition modes are stored by enum name; fromStored also accepts the historical labels.
    @TypeConverter
    fun overlapModeToString(mode: OverlapMode): String = mode.name

    @TypeConverter
    fun stringToOverlapMode(value: String?): OverlapMode = OverlapMode.fromStored(value)

    @TypeConverter
    fun eqModeToString(mode: EqMode): String = mode.name

    @TypeConverter
    fun stringToEqMode(value: String?): EqMode = EqMode.fromStored(value)

    @TypeConverter
    fun effectModeToString(mode: EffectMode): String = mode.name

    @TypeConverter
    fun stringToEffectMode(value: String?): EffectMode = EffectMode.fromStored(value)
}
