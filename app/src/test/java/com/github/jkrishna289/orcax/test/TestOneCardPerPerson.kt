package com.github.jkrishna289.orcax.test

import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.data.model.oneCardPerPerson
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.PersonKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TestOneCardPerPerson {
    private fun person(
        id: UUID,
        name: String,
        role: String?,
    ) = Person(id, name, role, PersonKind.ACTOR, null, false)

    @Test
    fun `merges a person listed once per role, keeping first-appearance order`() {
        val joe = UUID.randomUUID()
        val lois = UUID.randomUUID()
        val people =
            listOf(
                person(joe, "Patrick Warburton", "Joe Swanson (voice)"),
                person(lois, "Alex Borstein", "Lois Griffin"),
                person(joe, "Patrick Warburton", "Joe Swanson (voice)"),
                person(joe, "Patrick Warburton", "Superman"),
                person(joe, "Patrick Warburton", " "),
            )

        val merged = people.oneCardPerPerson()

        assertEquals(listOf(joe, lois), merged.map { it.id })
        assertEquals("Joe Swanson (voice) / Superman", merged[0].role)
        assertEquals("Lois Griffin", merged[1].role)
    }

    @Test
    fun `no roles stays no role`() {
        val id = UUID.randomUUID()
        val merged = listOf(person(id, "A", null), person(id, "A", "")).oneCardPerPerson()
        assertEquals(1, merged.size)
        assertEquals(null, merged[0].role)
    }
}
