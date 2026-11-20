package app.roadmaxxing.sync

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure

/**
 * Hand-written read side for sync documents. GitLive 2.8.0 can WRITE a raw `Map<String, Any?>` but cannot READ one
 * (`data<Map<String, Any?>>()` has no serializer for `Any`, and composeApp does not apply the serialization compiler
 * plugin). So reads go through a field table: every known field is decoded with its own primitive serializer and
 * returned in a plain map with Long/Double/String/Boolean/List/Map values.
 *
 * The tables mirror `SyncCodec` (shared) and the field whitelist in `firestore.rules`. A new document field must be
 * added in all three places; a field missing here makes decoding fail (as [SyncError.Unknown]).
 */
internal enum class FieldKind { STRING, LONG, DOUBLE, BOOLEAN, STRING_MAP, ZONES }

internal class FieldSpec(val name: String, val kind: FieldKind)

internal val DRIVE_FIELDS = listOf(
    FieldSpec("v", FieldKind.LONG), FieldSpec("start", FieldKind.LONG), FieldSpec("end", FieldKind.LONG),
    FieldSpec("verdict", FieldKind.STRING), FieldSpec("dist", FieldKind.DOUBLE), FieldSpec("dur", FieldKind.LONG),
    FieldSpec("avg", FieldKind.DOUBLE), FieldSpec("max", FieldKind.DOUBLE), FieldSpec("newDm", FieldKind.LONG),
    FieldSpec("route", FieldKind.STRING), FieldSpec("segs", FieldKind.STRING),
)

internal val PROFILE_FIELDS = listOf(
    FieldSpec("v", FieldKind.LONG), FieldSpec("updatedAt", FieldKind.LONG),
    FieldSpec("settings", FieldKind.STRING_MAP), FieldSpec("zones", FieldKind.ZONES),
)

private val ZONE_FIELDS = listOf(
    FieldSpec("k", FieldKind.STRING), FieldSpec("n", FieldKind.STRING), FieldSpec("la", FieldKind.DOUBLE),
    FieldSpec("lo", FieldKind.DOUBLE), FieldSpec("r", FieldKind.LONG),
)

private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

/** Decodes a flat/nested document described by [fields] into a plain map; absent fields are simply missing. */
internal class FieldTableSerializer(name: String, private val fields: List<FieldSpec>) :
    DeserializationStrategy<Map<String, Any?>> {

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name) {
        for (f in fields) {
            when (f.kind) {
                FieldKind.STRING -> element<String>(f.name, isOptional = true)
                FieldKind.LONG -> element<Long>(f.name, isOptional = true)
                FieldKind.DOUBLE -> element<Double>(f.name, isOptional = true)
                FieldKind.BOOLEAN -> element<Boolean>(f.name, isOptional = true)
                FieldKind.STRING_MAP -> element(f.name, stringMapSerializer.descriptor, isOptional = true)
                FieldKind.ZONES -> element(f.name, ListSerializer(ZoneSerializer).descriptor, isOptional = true)
            }
        }
    }

    override fun deserialize(decoder: Decoder): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        decoder.decodeStructure(descriptor) {
            while (true) {
                val i = decodeElementIndex(descriptor)
                if (i == CompositeDecoder.DECODE_DONE) break
                if (i < 0 || i >= fields.size) continue
                val f = fields[i]
                out[f.name] = when (f.kind) {
                    FieldKind.STRING -> decodeStringElement(descriptor, i)
                    FieldKind.LONG -> decodeLongElement(descriptor, i)
                    FieldKind.DOUBLE -> decodeDoubleElement(descriptor, i)
                    FieldKind.BOOLEAN -> decodeBooleanElement(descriptor, i)
                    FieldKind.STRING_MAP -> decodeSerializableElement(descriptor, i, stringMapSerializer)
                    FieldKind.ZONES -> decodeSerializableElement(descriptor, i, ListSerializer(ZoneSerializer))
                }
            }
        }
        return out
    }
}

private object ZoneSerializer : kotlinx.serialization.KSerializer<Map<String, Any?>> {
    private val table = FieldTableSerializer("Zone", ZONE_FIELDS)
    override val descriptor: SerialDescriptor get() = table.descriptor
    override fun deserialize(decoder: Decoder): Map<String, Any?> = table.deserialize(decoder)
    override fun serialize(encoder: Encoder, value: Map<String, Any?>) =
        throw UnsupportedOperationException("read-only")
}

internal val DriveDocSerializer = FieldTableSerializer("Drive", DRIVE_FIELDS)
internal val ProfileDocSerializer = FieldTableSerializer("Profile", PROFILE_FIELDS)
