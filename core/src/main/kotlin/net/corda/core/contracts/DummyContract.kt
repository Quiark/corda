package net.corda.core.contracts

import net.corda.core.crypto.Party
import net.corda.core.crypto.SecureHash
import net.corda.core.transactions.TransactionBuilder
import java.security.PublicKey

// The dummy contract doesn't do anything useful. It exists for testing purposes.

val DUMMY_PROGRAM_ID = DummyContract()

data class DummyContract(override val legalContractReference: SecureHash = SecureHash.sha256("")) : Contract {
    interface State : ContractState {
        val magicNumber: Int
    }

    data class SingleOwnerState(override val magicNumber: Int = 0, override val owner: PublicKey) : OwnableState, State {
        override val contract = DUMMY_PROGRAM_ID
        override val participants: List<PublicKey>
            get() = listOf(owner)

        override fun withNewOwner(newOwner: PublicKey) = Pair(Commands.Move(), copy(owner = newOwner))
    }

    /**
     * Alternative state with multiple owners. This exists primarily to provide a dummy state with multiple
     * participants, and could in theory be merged with [SingleOwnerState] by putting the additional participants
     * in a different field, however this is a good example of a contract with multiple states.
     */
    data class MultiOwnerState(override val magicNumber: Int = 0,
                               val owners: List<PublicKey>) : ContractState, State {
        override val contract = DUMMY_PROGRAM_ID
        override val participants: List<PublicKey> get() = owners
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
        class Move : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: TransactionForContract) {
        // Always accepts.
    }

    companion object {
        @JvmStatic
        fun generateInitial(magicNumber: Int, notary: Party, owner: PartyAndReference, vararg otherOwners: PartyAndReference): TransactionBuilder {
            val owners = listOf(owner) + otherOwners
            return if (owners.size == 1) {
                val state = SingleOwnerState(magicNumber, owners.first().party.owningKey)
                TransactionType.General.Builder(notary = notary).withItems(state, Command(Commands.Create(), owners.first().party.owningKey))
            } else {
                val state = MultiOwnerState(magicNumber, owners.map { it.party.owningKey })
                TransactionType.General.Builder(notary = notary).withItems(state, Command(Commands.Create(), owners.map { it.party.owningKey }))
            }
        }

        fun move(prior: StateAndRef<DummyContract.SingleOwnerState>, newOwner: PublicKey) = move(listOf(prior), newOwner)
        fun move(priors: List<StateAndRef<DummyContract.SingleOwnerState>>, newOwner: PublicKey): TransactionBuilder {
            require(priors.isNotEmpty())
            val priorState = priors[0].state.data
            val (cmd, state) = priorState.withNewOwner(newOwner)
            return TransactionType.General.Builder(notary = priors[0].state.notary).withItems(
                    /* INPUTS  */ *priors.toTypedArray(),
                    /* COMMAND */ Command(cmd, priorState.owner),
                    /* OUTPUT  */ state
            )
        }
    }
}
