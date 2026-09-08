package com.smartledger.nativeapp.ui

import com.smartledger.nativeapp.R
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryIconTest {
    @Test fun unknownIconFallsBackToQuestionMark() = assertEquals(R.drawable.ic_lucide_circle_question_mark, lucideDrawable("not-a-lucide-icon"))
}
