package net.corda.node.services.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.node.services.api.SchemaService
import net.corda.node.services.api.ExtQueryableState
import net.corda.core.contracts.StateRef
import org.hibernate.StatelessSession


/**
 * Most basic implementation of [SchemaService].
 *
 * TODO: support loading schema options from node configuration.
 * TODO: support configuring what schemas are to be selected for persistence.
 * TODO: support plugins for schema version upgrading or custom mapping not supported by original [QueryableState].
 */
class NodeSchemaService : SchemaService, SingletonSerializeAsToken() {
    // Currently does not support configuring schema options.
    override val schemaOptions: Map<MappedSchema, SchemaService.SchemaOptions> = emptyMap()

    // Currently returns all schemas supported by the state, with no filtering or enrichment.
    override fun selectSchemas(state: QueryableState): Iterable<MappedSchema> {
        return state.supportedSchemas()
    }

    // Because schema is always one supported by the state, just delegate.
    override fun generateMappedObject(state: QueryableState, schema: MappedSchema, session: StatelessSession): PersistentState {
        return state.generateMappedObject(schema)
    }

	override fun persistExtState(stateRef: StateRef, state: ExtQueryableState, schema: MappedSchema, session: StatelessSession) {
		state.persistEx(stateRef, schema, session)
	}
}
