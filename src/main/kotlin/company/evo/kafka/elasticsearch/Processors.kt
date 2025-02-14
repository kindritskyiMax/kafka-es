package company.evo.kafka.elasticsearch

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat

import company.evo.kafka.castOrFail
import company.evo.kafka.elasticsearch.BulkActionProto.BulkAction


interface Processor {
    fun process(value: Any, index: String?): AnyBulkableAction
}

class JsonProcessor : Processor {
    override fun process(value: Any, index: String?): AnyBulkableAction {
        val valueOrPayload = castOrFail<Map<*, *>>(value)
        val payload: Map<*, *> = if (valueOrPayload.containsKey("payload")) {
            castOrFail(valueOrPayload["payload"], "payload")
        } else {
            valueOrPayload
        }
        val actionData: Map<*, *> = castOrFail(payload["action"], "action")
        val actionBuilder = AnyBulkableAction.Builder(actionData)
        val actionEntry = actionData.iterator().next()
        when (actionEntry.key) {
            "index", "create", "update" -> {
                val sourceData: Map<*, *> = castOrFail(payload["source"], "source")
                actionBuilder.setSource(sourceData)
            }
            "delete" -> {}
            else -> {
                throw IllegalArgumentException(
                        "Expected one of the action [index, create, update, delete] " +
                                "but was [${actionEntry.key}]")
            }
        }
        if (index?.isNotEmpty() == true) {
            actionBuilder.index(index)
        }
        return actionBuilder.build()
    }
 }

class ProtobufProcessor(
        includeDefaultValues: Boolean = true
) : Processor {
    private val jsonPrinter: JsonFormat.Printer
    init {
        var jsonPrinter = JsonFormat.printer()
                .omittingInsignificantWhitespace()
                .preservingProtoFieldNames()
        if (includeDefaultValues) {
            jsonPrinter = jsonPrinter.includingDefaultValueFields()
        }
        this.jsonPrinter = jsonPrinter
    }

    override fun process(value: Any, index: String?): AnyBulkableAction {
        val message = castOrFail<MessageOrBuilder>(value)
        val descriptor = message.descriptorForType
        val actionField = descriptor.findFieldByName("action") ?:
                throw IllegalArgumentException("Message must contain [action] field")
        val action = message.getField(actionField) as? BulkAction ?:
                throw IllegalArgumentException(
                        "[action] field must be an instance of the ${BulkAction::class.java}")
        val actionBuilder = AnyBulkableAction.Builder(action)
        if (index?.isNotEmpty() == true) {
            actionBuilder.index(index)
        }
        when (action.opType) {
            BulkAction.OpType.INDEX, BulkAction.OpType.UPDATE, BulkAction.OpType.CREATE -> {
                val sourceField = descriptor.findFieldByName("source") ?:
                        throw IllegalArgumentException("Message must contain [source] field")
                val source = message.getField(sourceField) as? Message ?:
                        throw IllegalArgumentException(
                                "[source] field must be an instance of the ${Message::class.java}")
                actionBuilder.setSource(jsonPrinter.print(source))
            }
            BulkAction.OpType.DELETE -> {}
            BulkAction.OpType.UNRECOGNIZED, null -> {
                throw IllegalArgumentException("Unrecognized operation type for bulk action")
            }
        }
        return actionBuilder.build()
    }
}
