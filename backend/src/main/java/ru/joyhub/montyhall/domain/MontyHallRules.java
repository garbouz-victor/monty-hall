package ru.joyhub.montyhall.domain;

import java.util.ArrayList;
import java.util.List;

public final class MontyHallRules {

    public static final int BOX_COUNT = 3;

    private MontyHallRules() {
    }

    public static HostMove hostMove(int keyBox, int initialChoice, IntRandomSource random) {
        validateBox(keyBox);
        validateBox(initialChoice);

        List<Integer> candidates = new ArrayList<>(2);
        for (int box = 1; box <= BOX_COUNT; box++) {
            if (box != keyBox && box != initialChoice) {
                candidates.add(box);
            }
        }

        int openedBox = candidates.size() == 1
                ? candidates.getFirst()
                : candidates.get(random.nextInt(candidates.size()));
        return new HostMove(openedBox, remainingClosedBox(initialChoice, openedBox));
    }

    public static int finalChoice(int initialChoice, int openedBox, Strategy strategy) {
        validateBox(initialChoice);
        validateBox(openedBox);
        if (initialChoice == openedBox) {
            throw new IllegalArgumentException("The host cannot open the selected box");
        }
        return strategy == Strategy.STAY
                ? initialChoice
                : remainingClosedBox(initialChoice, openedBox);
    }

    private static int remainingClosedBox(int first, int second) {
        for (int box = 1; box <= BOX_COUNT; box++) {
            if (box != first && box != second) {
                return box;
            }
        }
        throw new IllegalStateException("No closed box remains");
    }

    public static void validateBox(int box) {
        if (box < 1 || box > BOX_COUNT) {
            throw new IllegalArgumentException("Box must be between 1 and 3");
        }
    }
}

