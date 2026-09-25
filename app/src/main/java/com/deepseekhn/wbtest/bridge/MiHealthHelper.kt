package com.deepseekhn.wbtest.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 小米运动健康（国内 com.xiaomi.hm.health / 国际 Mi Fitness com.mi.health）集成 */
object MiHealthHelper {

    private val candidates = listOf("com.mi.health", "com.xiaomi.hm.health")

    fun installedPackage(context: Context): String? {
        for (pkg in candidates) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /** 打开小米运动健康；未安装则引导到应用市场。返回是否成功拉起。 */
    fun open(context: Context): Boolean {
        val pkg = installedPackage(context)
        return if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } else {
            // 未安装：跳商店搜索
            try {
                val market = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.mi.health")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(market)
            } catch (_: Exception) {
                Toast.makeText(context, "未找到小米运动健康，且无应用市场", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}
