package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

/** Examples: what the search bar handles on the phone, with no AI and no cost. */
class LocalCommandParserTest {
    private val parse = LocalCommandParser::parse

    @ParameterizedTest
    @CsvSource(
        "timer 10 min, 600",
        "timer 90s, 90",
        "set a timer for 2 hours, 7200",
        "TIMER 5 minutes, 300",
    )
    fun `timers`(input: String, seconds: Int) =
        assertEquals(LocalCommand.Timer(seconds), parse(input))

    @ParameterizedTest
    @CsvSource(
        "alarm 7, 7, 0",
        "alarm 7:30, 7, 30",
        "alarm 6.45pm, 18, 45",
        "wake me at 12am, 0, 0",
        "alarm 12pm, 12, 0",
    )
    fun `alarms`(input: String, hour: Int, minute: Int) =
        assertEquals(LocalCommand.Alarm(hour, minute), parse(input))

    @ParameterizedTest
    @CsvSource(
        "'2+3*4', 14",
        "'(2+3)*4', 20",
        "'10 / 4', 2.5",
        "'-3 + 1', -2",
        "'2^10', 1024",
    )
    fun `maths`(input: String, result: Double) =
        assertEquals(LocalCommand.Calculate(result), parse(input))

    @Test
    fun `settings toggles`() {
        assertEquals(LocalCommand.Setting(SettingKind.WIFI, on = false), parse("wifi off"))
        assertEquals(LocalCommand.Setting(SettingKind.BLUETOOTH, on = true), parse("turn bluetooth on"))
        assertEquals(LocalCommand.Setting(SettingKind.TORCH, on = true), parse("torch on"))
    }

    @Test
    fun `anything else goes to the AI untouched`() {
        assertEquals(LocalCommand.AskAi("will i need a coat tomorrow"), parse("will i need a coat tomorrow"))
        assertEquals(LocalCommand.AskAi("timer for the oven when the cake is done"), parse("timer for the oven when the cake is done"))
    }

    @Test
    fun `nonsense maths is not evaluated and never throws`() {
        assertEquals(LocalCommand.AskAi("2+"), parse("2+"))
        assertEquals(LocalCommand.AskAi("1/0"), parse("1/0"))
        assertEquals(LocalCommand.AskAi(""), parse("   "))
    }
}
