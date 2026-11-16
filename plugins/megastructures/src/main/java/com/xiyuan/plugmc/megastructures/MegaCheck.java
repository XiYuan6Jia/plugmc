package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;

/** /mega check 使用的结构采样点：世界坐标 + 期望方块。 */
public record MegaCheck(String label, int x, int y, int z, Material expected) {
}
