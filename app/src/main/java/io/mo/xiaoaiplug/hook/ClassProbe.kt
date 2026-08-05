package io.mo.xiaoaiplug.hook

import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * 运行时类探测:候选类名全部失败时,扫描小爱 APK 的 dex 按特征定位类。
 *
 * 混淆只改类名/方法名,**不碰字符串字面量和未混淆的 API 类型**(Instruction 等)。
 * 所以特征锚点选:方法内引用的字符串、方法签名里的未混淆类型、Kotlin object 结构。
 * 这套特征在本项目逆向的两代小爱(7.513.23.0010 / 7.12.2.0318)上全部命中,
 * 大多数版本更新可以自动适配,不用重新人工逆向。
 */
object ClassProbe {

    private const val TAG = "ClassProbe"

    /** 一个 hook 点的特征定义。全部条件都满足才认为类匹配。 */
    data class Feature(
        val name: String,
        /** 类的方法体内引用的字符串字面量(混淆不碰字符串,最可靠的锚点)。 */
        val requiredStrings: List<String> = emptyList(),
        /** 方法名锚点(未混淆或保留的方法名,如 sendStreamData)。 */
        val requiredMethodNames: List<String> = emptyList(),
        /** 方法名 + 参数个数(如 g0(int) = ("g0", 1))。 */
        val methodArity: List<Pair<String, Int>> = emptyList(),
        /** Kotlin object 单例(静态字段类型等于类自身)。 */
        val requireSingleton: Boolean = false
    )

    // ---------------- dex 解析结果缓存 ----------------

    private class DexInfo(
        val strings: List<String>,
        val classes: List<ClassInfo>
    )

    private class ClassInfo(
        val name: String,           // Lcom/foo/Bar;
        val methods: List<String>,
        val stringRefs: Set<String>,
        val hasStaticSelfField: Boolean
    )

    @Volatile private var cached: DexInfo? = null
    private val cacheLock = Any()

    /**
     * 按特征找类。返回 null = 没找到(调用方保持不 hook,静默降级)。
     * [apkPath] 是小爱 APK 路径(ApplicationInfo.sourceDir)。
     */
    fun find(apkPath: String, feature: Feature): String? {
        val info = dexInfo(apkPath) ?: return null
        for (c in info.classes) {
            if (!matches(c, feature)) continue
            // 排除内部类(它们通常与外部类同特征,外部类才是目标)
            if ('$' in c.name) continue
            Log.i(TAG, "${feature.name}: matched ${c.name}")
            return c.name
        }
        Log.i(TAG, "${feature.name}: no class matched (probe missed)")
        return null
    }

    private fun matches(c: ClassInfo, f: Feature): Boolean {
        if (f.requiredStrings.isNotEmpty() && !c.stringRefs.containsAll(f.requiredStrings)) return false
        if (f.requiredMethodNames.isNotEmpty() && !c.methods.containsAll(f.requiredMethodNames)) return false
        for ((name, arity) in f.methodArity) {
            if (c.methods.count { it == name } < arity) return false
        }
        if (f.requireSingleton && !c.hasStaticSelfField) return false
        return true
    }

    // ---------------- dex 解析 ----------------

    private fun dexInfo(apkPath: String): DexInfo? {
        cached?.let { return it }
        synchronized(cacheLock) {
            cached?.let { return it }
            val info = try { parseApk(apkPath) } catch (t: Throwable) {
                Log.w(TAG, "dex parse failed: $t")
                null
            }
            cached = info
            return info
        }
    }

    private fun parseApk(apkPath: String): DexInfo? {
        val apk = File(apkPath)
        if (!apk.exists()) return null
        val allStrings = mutableListOf<String>()
        val allClasses = mutableListOf<ClassInfo>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                .sortedBy { it.name }
                .toList()
            for (entry in entries) {
                val data = zip.getInputStream(entry).readBytes()
                parseDex(data)?.let { (strings, classes) ->
                    allStrings += strings
                    allClasses += classes
                }
            }
        }
        if (allClasses.isEmpty()) return null
        return DexInfo(allStrings, allClasses)
    }

    /** 解析单个 dex:字符串表 + 类定义(方法名/字符串引用/单例字段)。 */
    private fun parseDex(data: ByteArray): Pair<List<String>, List<ClassInfo>>? {
        if (data.size < 8 || data[0] != 'd'.code.toByte() || data[1] != 'e'.code.toByte()) return null

        fun u32(off: Int): Int = (data[off].toInt() and 0xff) or
            ((data[off + 1].toInt() and 0xff) shl 8) or
            ((data[off + 2].toInt() and 0xff) shl 16) or
            ((data[off + 3].toInt() and 0xff) shl 24)

        fun uleb(off: Int): Pair<Int, Int> {
            var result = 0; var shift = 0; var p = off
            while (true) {
                val b = data[p].toInt() and 0xff; p++
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result to p
        }

        val stringIdsSize = u32(0x38); val stringIdsOff = u32(0x3c)
        val typeIdsSize = u32(0x40); val typeIdsOff = u32(0x44)
        val fieldIdsSize = u32(0x50); val fieldIdsOff = u32(0x54)
        val methodIdsSize = u32(0x58); val methodIdsOff = u32(0x5c)
        val classDefsSize = u32(0x60); val classDefsOff = u32(0x64)

        // 字符串表
        val strings = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val off = u32(stringIdsOff + i * 4)
            val (len, p) = uleb(off)
            strings.add(String(data, p, len, Charsets.UTF_8))
        }
        // 类型表(descriptor -> string idx)
        val types = IntArray(typeIdsSize)
        for (i in 0 until typeIdsSize) types[i] = u32(typeIdsOff + i * 4)
        // 字段表(名称)
        val fieldNames = ArrayList<String>(fieldIdsSize)
        for (i in 0 until fieldIdsSize) {
            val nameIdx = u32(fieldIdsOff + i * 8 + 4)
            fieldNames.add(if (nameIdx < strings.size) strings[nameIdx] else "?")
        }
        // 字段类型(用于单例判定:静态字段类型 == 类自身)
        val fieldTypes = IntArray(fieldIdsSize)
        for (i in 0 until fieldIdsSize) fieldTypes[i] = u32(fieldIdsOff + i * 8 + 2)
        // 方法表(名称)
        val methodNames = ArrayList<String>(methodIdsSize)
        for (i in 0 until methodIdsSize) {
            val nameIdx = u32(methodIdsOff + i * 8 + 4)
            methodNames.add(if (nameIdx < strings.size) strings[nameIdx] else "?")
        }

        val classes = ArrayList<ClassInfo>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val classIdx = u32(base)
            val classDataOff = u32(base + 24)
            if (classIdx >= types.size || classDataOff == 0) continue
            val desc = if (types[classIdx] < strings.size) strings[types[classIdx]] else "?"
            var p = classDataOff
            val (sfs, p1) = uleb(p); p = p1
            val (ifs, p2) = uleb(p); p = p2
            val (dm, p3) = uleb(p); p = p3
            val (vm, p4) = uleb(p); p = p4

            // 字段:找静态自引用字段(Kotlin object 单例)
            var fidx = 0; var hasStaticSelf = false
            for (k in 0 until sfs + ifs) {
                val (diff, p5) = uleb(p); fidx += diff; p = p5
                val (_, p6) = uleb(p); p = p6
                if (k < sfs && fidx < fieldIdsSize) {
                    val ft = fieldTypes[fidx]
                    if (ft < types.size && types[ft] == types[classIdx] &&
                        fieldNames[fidx] == "a"  // Kotlin object 的 INSTANCE 混淆后常见名
                    ) hasStaticSelf = true
                }
            }

            // 方法:名字 + code 里的 const-string 引用
            var midx = 0
            val methods = ArrayList<String>(dm + vm)
            val refs = HashSet<String>()
            for (k in 0 until dm + vm) {
                val (diff, p5) = uleb(p); midx += diff; p = p5
                val (_, p6) = uleb(p); p = p6
                val (codeOff, p7) = uleb(p); p = p7
                if (midx < methodNames.size) methods.add(methodNames[midx])
                if (codeOff != 0) {
                    val insnsSize = u32(codeOff + 12)
                    val start = codeOff + 16
                    val end = start + insnsSize * 2
                    var j = start
                    while (j + 3 <= end) {
                        val op = data[j].toInt() and 0xff
                        if (op == 0x1a && j + 4 <= end) {  // const-string
                            val sIdx = (data[j + 2].toInt() and 0xff) or
                                ((data[j + 3].toInt() and 0xff) shl 8)
                            if (sIdx < strings.size) refs.add(strings[sIdx])
                            j += 4
                        } else if (op == 0x1b && j + 6 <= end) {  // const-string/jumbo
                            val sIdx = (data[j + 2].toInt() and 0xff) or
                                ((data[j + 3].toInt() and 0xff) shl 8) or
                                ((data[j + 4].toInt() and 0xff) shl 16) or
                                ((data[j + 5].toInt() and 0xff) shl 24)
                            if (sIdx < strings.size) refs.add(strings[sIdx])
                            j += 6
                        } else {
                            j += opcodeSize(op)
                        }
                    }
                }
            }
            classes.add(ClassInfo(desc, methods, refs, hasStaticSelf))
        }
        return strings to classes
    }

    /** ART opcode 长度表(字节)。只影响扫描效率,解析错位概率很低。 */
    private fun opcodeSize(op: Int): Int = when {
        op <= 0x0e || op == 0x12 || op == 0x1d || op == 0x1e || op == 0x21 || op == 0x27 -> 1
        op == 0x02 || op == 0x05 || op == 0x08 || op == 0x13 || op == 0x15 || op == 0x16 ||
            op == 0x19 || op == 0x28 -> 2
        op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x1a || op == 0x1c ||
            op == 0x1f || op == 0x20 || op == 0x22 || op == 0x23 || op == 0x24 || op == 0x25 ||
            op == 0x26 || op == 0x29 || op == 0x2b || op == 0x2c -> 3
        op == 0x2a -> 5
        op in 0x32..0x3d -> 2
        op in 0x40..0xaf -> 1
        op in 0xb0..0xef -> 2
        op in 0xf0..0x12f -> 3
        op in 0x130..0x26f -> 3
        op in 0x270..0x61f -> 1
        op in 0x620..0x9af -> 2
        op in 0x9b0..0x9df -> 2
        op in 0x9e0..0xa6f -> 2
        op in 0xa70..0xacf -> 3
        op in 0xad0..0xb8f -> 3
        op in 0xb90..0xbbf -> 2
        op in 0xbc0..0xc4f -> 3
        op in 0xc50..0xc7f -> 3
        op in 0xd00..0xd3f -> 5
        op in 0xd40..0xd47 -> 3
        else -> 1
    }
}
