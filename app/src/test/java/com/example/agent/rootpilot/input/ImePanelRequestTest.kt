package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.model.ConfirmationStage
import com.example.agent.rootpilot.model.ConfirmationUi
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import org.junit.Assert.*
import org.junit.Test

class ImePanelRequestTest {
    private val type = RootPilotAction.Type("执行模式验收通过", "fixture")
    private val commit = RootPilotUiState(
        confirmation = ConfirmationUi(ConfirmationStage.TYPE_COMMIT, "token"),
        status = RootPilotStatus.WAITING_CONFIRMATION,
        pendingAction = type,
    )

    @Test fun requestsOnlyForUnlockedBoundTypeCommit() {
        assertEquals("token", commitPanelToken(commit, locked = false, boundToCurrentConnection = true))
        assertNull(commitPanelToken(commit, locked = true, boundToCurrentConnection = true))
        assertNull(commitPanelToken(commit, locked = false, boundToCurrentConnection = false))
    }

    @Test fun neverRequestsForPreparationActionOrMissingToken() {
        for (state in listOf(
            commit.copy(confirmation = ConfirmationUi(ConfirmationStage.TYPE_PREPARE)),
            commit.copy(confirmation = ConfirmationUi(ConfirmationStage.ACTION)),
            commit.copy(confirmation = ConfirmationUi(ConfirmationStage.TYPE_COMMIT)),
            commit.copy(confirmation = null),
            commit.copy(status = RootPilotStatus.EXECUTING),
            commit.copy(pendingAction = RootPilotAction.Wait(1, "wait")),
            commit.copy(pendingAction = null),
        )) assertNull(commitPanelToken(state, locked = false, boundToCurrentConnection = true))
    }
}
