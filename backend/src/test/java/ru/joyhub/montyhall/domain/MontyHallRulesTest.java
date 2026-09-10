package ru.joyhub.montyhall.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class MontyHallRulesTest {

    @ParameterizedTest
    @CsvSource({
            "1,1", "1,2", "1,3",
            "2,1", "2,2", "2,3",
            "3,1", "3,2", "3,3"
    })
    void hostNeverOpensKeysOrInitialChoice(int keyBox, int choice) {
        for (int randomPick = 0; randomPick < 2; randomPick++) {
            int pick = randomPick;
            HostMove move = MontyHallRules.hostMove(keyBox, choice, bound -> pick % bound);

            assertThat(move.openedBox()).isBetween(1, 3);
            assertThat(move.openedBox()).isNotEqualTo(keyBox);
            assertThat(move.openedBox()).isNotEqualTo(choice);
            assertThat(move.switchToBox()).isNotEqualTo(choice);
            assertThat(move.switchToBox()).isNotEqualTo(move.openedBox());
        }
    }

    @Test
    void hostUsesTheOnlyPossibleEmptyBox() {
        HostMove move = MontyHallRules.hostMove(2, 3, bound -> {
            throw new AssertionError("Random must not be used when only one box is available");
        });

        assertThat(move).isEqualTo(new HostMove(1, 2));
    }

    @Test
    void hostRandomlySelectsBetweenTwoEmptyBoxesWhenInitialChoiceIsCorrect() {
        HostMove first = MontyHallRules.hostMove(3, 3, bound -> 0);
        HostMove second = MontyHallRules.hostMove(3, 3, bound -> 1);

        assertThat(first.openedBox()).isEqualTo(1);
        assertThat(second.openedBox()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"1,2,3", "1,3,2", "2,1,3", "2,3,1", "3,1,2", "3,2,1"})
    void switchChoosesTheOtherClosedBox(int initialChoice, int openedBox, int expectedFinal) {
        assertThat(MontyHallRules.finalChoice(initialChoice, openedBox, Strategy.SWITCH))
                .isEqualTo(expectedFinal);
    }

    @ParameterizedTest
    @CsvSource({"1,2", "1,3", "2,1", "2,3", "3,1", "3,2"})
    void stayKeepsInitialChoice(int initialChoice, int openedBox) {
        assertThat(MontyHallRules.finalChoice(initialChoice, openedBox, Strategy.STAY))
                .isEqualTo(initialChoice);
    }

    @Test
    void monteCarloConvergesToTheoreticalProbabilities() {
        SplittableRandom random = new SplittableRandom(8_675_309L);
        int simulations = 250_000;
        int switchWins = 0;
        int stayWins = 0;

        for (int i = 0; i < simulations; i++) {
            int keyBox = random.nextInt(1, 4);
            int initialChoice = random.nextInt(1, 4);
            HostMove move = MontyHallRules.hostMove(keyBox, initialChoice, random::nextInt);

            int switched = MontyHallRules.finalChoice(initialChoice, move.openedBox(), Strategy.SWITCH);
            int stayed = MontyHallRules.finalChoice(initialChoice, move.openedBox(), Strategy.STAY);
            switchWins += switched == keyBox ? 1 : 0;
            stayWins += stayed == keyBox ? 1 : 0;
        }

        assertThat((double) switchWins / simulations).isCloseTo(2.0 / 3.0, within(0.005));
        assertThat((double) stayWins / simulations).isCloseTo(1.0 / 3.0, within(0.005));
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}

