package com.geeui.voice.skill

import com.geeui.voice.bus.RobotBus

/**
 * Same job as BotService.dealCommand: a known skill hits the robot bus,
 * anything else is spoken by the caller (local LLM or a fixed sentence).
 * Motion 98 step 3 speed 2 is the walk validated on the robot.
 * 64 is only the SDK neighbour of 63. It is not confirmed on this device.
 */
class SkillRouter(
    private val bus: RobotBus,
    private val charging: () -> Boolean = { false },
) {
    fun handle(hit: SkillHit): Boolean {
        when (hit.id) {
            "actin" -> {
                if (charging()) {
                    bus.speakText("Je charge, je ne peux pas marcher.")
                    return true
                }
                val number = hit.slots["number"]?.toIntOrNull() ?: return false
                val step = hit.slots["step"]?.toIntOrNull() ?: 3
                val speed = hit.slots["speed"]?.toIntOrNull() ?: 2
                bus.controlMotion(number, step, speed)
            }
            "volumeUp" -> bus.speechCmd("DUI.MediaController.SetVolume", "+")
            "volumeDown" -> bus.speechCmd("DUI.MediaController.SetVolume", "-")
            "volumemax" -> bus.speechCmd("DUI.MediaController.SetVolume", "100")
            "volumemix" -> bus.speechCmd("DUI.MediaController.SetVolume", "0")
            "volumeadjust" -> {
                val value = hit.slots["value"] ?: return false
                bus.speechCmd("DUI.MediaController.SetVolume", value)
            }
            "takePhoto" -> bus.speechCmd("rhj.controller.takephoto", "1")
            "goHome" -> bus.speechCmd("rhj.controller.openApp", "自动回充")
            "reboot" -> bus.speechCmd("DUI.System.Reboot", "1")
            "shutdown" -> bus.speechCmd("DUI.System.Shutdown", "1")
            "face" -> {
                val id = hit.slots["id"] ?: return false
                bus.showFace(id)
            }
            "open" -> {
                val pkg = hit.slots["package"] ?: return false
                bus.speechCmd("rhj.controller.openApp", pkg)
            }
            else -> return false
        }
        return true
    }
}
