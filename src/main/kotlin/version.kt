import com.google.common.collect.ImmutableList
import com.google.common.collect.MultimapBuilder
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.techcable.srglib.FieldData
import net.techcable.srglib.JavaType
import net.techcable.srglib.MethodData
import net.techcable.srglib.format.MappingsFormat
import net.techcable.srglib.mappings.Mappings
import provider.*
import java.io.File

enum class MinecraftVersion(
    val mcVersion: String,
    val mcpVersion: String? = null,
    val mcpConfig: Boolean = false,
    val yarn: Boolean = false,
    val spigot: Boolean = false
) {
    V1_16_4("1.16.4", null, true, true, true),
    V1_16_3("1.16.3", "snapshot_20201025", true, false, true),
    V1_16_3_YARN("1.16.3", null, false, true, true),
    V1_16_2("1.16.2", "snapshot_20200812", true, false, true),
    V1_16_2_YARN("1.16.2", null, false, true, true),
    V1_16_1_YARN("1.16.1", null, false, true, true),
    V1_16_1("1.16.1", "snapshot_20200625", false, true, true),
    V1_15_2("1.15.2", "snapshot_20200515", true, false, true),
    V1_15_1("1.15.1", "snapshot_20191217", true, false, true),
    V1_15("1.15", "snapshot_nodoc_20191212", true, false, true),
    V1_14_4("1.14.4", "snapshot_nodoc_20190719", true, false, true),
    V1_14_3("1.14.3", "snapshot_nodoc_20190719", true, true, true),
    V1_14_2("1.14.2", "stable_nodoc_53", false, true, true),
    V1_14_1("1.14.1", "stable_nodoc_51", false, true, true),
    V1_14("1.14", "stable_nodoc_49", false, true, true),
    V1_13_2("1.13.2", "stable_nodoc_47", false, false, true),
    V1_13_1("1.13.1", "stable_nodoc_45", false, false, true),
    V1_13("1.13", "stable_nodoc_43", false, false, true),
    V1_12_2("1.12.2", "snapshot_nodoc_20171003", true, false, true),
    V1_12("1.12", "snapshot_nodoc_20180814", false, false, true),
    V1_11("1.11", "snapshot_nodoc_20170612", false, false, true),
    V1_10_2("1.10.2", "snapshot_nodoc_20160703", false, false, true),
    V1_9_4("1.9.4", "snapshot_nodoc_20160604", false, false, true),
    V1_9("1.9", "snapshot_nodoc_20160516", false, false, true),
    V1_8_8("1.8.8", "snapshot_nodoc_20151216", false, false, true),
    V1_8("1.8", "snapshot_nodoc_20141130", false, false, true),
    V1_7_10("1.7.10", "snapshot_nodoc_20140925", true, false, false);

    fun generateMappings(): List<Pair<String, Mappings>> {
        // Mappings, fromObf
        val mappings = mutableListOf<Pair<Mappings, String>>()

        if (mcpVersion != null) {
            println("Reading MCP names")
            val obf2srgMappings = if (mcpConfig) {
                getMCPConfigMappings(mcVersion)
            } else {
                downloadSrgMappings(mcVersion)
            }
            val srg2mcpMappings = downloadMcpMappings(obf2srgMappings, mcpVersion, mcVersion)
            val obf2mcp = Mappings.chain(ImmutableList.of(obf2srgMappings, srg2mcpMappings))
            mappings.add(Pair(obf2srgMappings, "srg"))
            mappings.add(Pair(obf2mcp, "mcp"))
        }
        if (spigot) {
            println("Reading spigot mappings")
            val buildDataCommit = getBuildDataCommit(mcVersion)
            val obf2spigotMappings = downloadSpigotMappings(buildDataCommit)
            mappings.add(Pair(obf2spigotMappings, "spigot"))
        }
        if (yarn) {
            println("Reading yarn mappings")
            val obf2yarnMappingsSet = getYarnMappings(mcVersion)
            obf2yarnMappingsSet.forEach { (id, m) -> mappings.add(Pair(m, id)) }
        }

        println("Merging mappings")
        val completeMappings = mutableListOf<Pair<String, Mappings>>()
        for (a in mappings) {
            val obf2aMappings = a.first
            val a2obfMappings = obf2aMappings.inverted()

            completeMappings.add(Pair("obf2${a.second}", obf2aMappings))
            completeMappings.add(Pair("${a.second}2obf", a2obfMappings))
            for (b in mappings) {
                if (a != b) {
                    val a2bMappings = Mappings.chain(a2obfMappings, b.first)
                    completeMappings.add(Pair("${a.second}2${b.second}", a2bMappings))
                }
            }
        }
        return completeMappings
    }

    fun write(mappingsFolder: File) {
        val outputFolder = File(mappingsFolder, mcVersion)
        outputFolder.mkdirs()

        fun Mappings.writeTo(fileName: String) {
            println("$mcVersion: writing mappings to $fileName.srg")
            val strippedMappings = stripDuplicates(this)
            val srgLines = MappingsFormat.SEARGE_FORMAT.toLines(strippedMappings)
            srgLines.sort()
            val file = File(outputFolder, "$fileName.srg")
            file.bufferedWriter().use {
                for (line in srgLines) {
                    it.write(line)
                    it.write("\n")
                }
            }

            println("$mcVersion: writing mappings to $fileName.csrg")
            val csrgLines = MappingsFormat.COMPACT_SEARGE_FORMAT.toLines(strippedMappings)
            csrgLines.sort()
            File(outputFolder, "$fileName.csrg").bufferedWriter().use {
                for (line in csrgLines) {
                    it.write(line)
                    it.write("\n")
                }
            }

            println("$mcVersion: writing mappings to $fileName.tsrg")
            TSrgUtil.fromSrg(file, File(outputFolder, "$fileName.tsrg"))
        }

        val generatedMappings = generateMappings()

        // tiny
        println("$mcVersion: writing tiny mappings to $mcVersion.tiny")
        val tinyMappings = tiny.Mappings()
        generatedMappings.filter { it.first.startsWith("obf2") }.forEach { pair ->
            val name = pair.first.split("2")[1]
            tinyMappings.addMappings(name, pair.second)
        }
        tinyMappings.fixupMappings()
        tinyMappings.fixSpigotPackages()
        File(outputFolder, "$mcVersion.tiny").bufferedWriter().use {
            for (line in tinyMappings.toStrings()) {
                it.write(line)
                it.write("\n")
            }
        }

        // json
        val classMappings =
            MultimapBuilder.hashKeys(1000).arrayListValues().build<JavaType, Pair<String, JavaType>>()
        val fieldMappings =
            MultimapBuilder.hashKeys(1000).arrayListValues().build<FieldData, Pair<String, FieldData>>()
        val methodMappings =
            MultimapBuilder.hashKeys(1000).arrayListValues().build<MethodData, Pair<String, MethodData>>()
        generatedMappings.filter { it.first.startsWith("obf2") }.forEach { pair ->
            val name = pair.first.split("2")[1]
            val mappings = pair.second
            mappings.forEachClass { obf, mapped -> classMappings.put(obf, Pair(name, mapped)) }
            mappings.forEachField { obf, mapped -> fieldMappings.put(obf, Pair(name, mapped)) }
            mappings.forEachMethod { obf, mapped -> methodMappings.put(obf, Pair(name, mapped)) }
            println("$mcVersion: generating json for $name")
        }

        fun String.lp(): String = split(".").last()

        val classArray = JsonArray()
        val fieldArray = JsonArray()
        val methodArray = JsonArray()
        for (obf in classMappings.keySet()) {
            val mappedObj = JsonObject()
            mappedObj.addProperty("obf", obf.name.lp())
            classMappings.get(obf).forEach {
                mappedObj.addProperty(it.first, it.second.name.lp())
            }
            classArray.add(mappedObj)
        }
        for (obf in fieldMappings.keySet()) {
            val mappedObj = JsonObject()
            mappedObj.addProperty("obf", obf.declaringType.name.lp() + "." + obf.name.lp())
            fieldMappings.get(obf).forEach {
                mappedObj.addProperty(it.first, it.second.declaringType.name.lp() + "." + it.second.name)
            }
            fieldArray.add(mappedObj)
        }
        for (obf in methodMappings.keySet()) {
            val mappedObj = JsonObject()
            mappedObj.addProperty("obf", obf.declaringType.name.lp() + "." + obf.name.lp())
            methodMappings.get(obf).forEach {
                mappedObj.addProperty(it.first, it.second.declaringType.name.lp() + "." + it.second.name)
            }
            methodArray.add(mappedObj)
        }

        val bigJson = JsonObject()
        bigJson.addProperty("minecraftVersion", mcVersion)
        bigJson.add("classes", classArray)
        bigJson.add("fields", fieldArray)
        bigJson.add("methods", methodArray)
        File(outputFolder, "$mcVersion.json").writeText(Gson().toJson(bigJson))
    }
}
