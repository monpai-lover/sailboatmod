package com.monpai.sailboatmod.resident.model;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Culture-aware name generation (inspired by MineColonies CitizenNameFile)
 */
public final class ResidentNames {

    private static final Map<Culture, String[]> MALE_FIRST = Map.of(
        Culture.EUROPEAN, new String[]{"Arthur", "William", "Edward", "Henry", "Charles", "George", "Thomas", "James", "Richard", "Robert", "Walter", "Frederick", "Albert", "Victor", "Edmund"},
        Culture.ASIAN, new String[]{"Chen", "Wei", "Ming", "Hiro", "Kenji", "Takeshi", "Yuki", "Ryu", "Jin", "Kai", "Hao", "Liang", "Tao", "Akira", "Sho"},
        Culture.NORDIC, new String[]{"Bjorn", "Erik", "Olaf", "Leif", "Ragnar", "Sigurd", "Harald", "Torsten", "Gunnar", "Sven", "Dag", "Ulf", "Ivar", "Nils", "Per"},
        Culture.DESERT, new String[]{"Khalid", "Omar", "Amir", "Rashid", "Tariq", "Faris", "Zain", "Nadir", "Samir", "Hamza", "Idris", "Malik", "Rami", "Yusuf", "Karim"},
        Culture.TROPICAL, new String[]{"Diego", "Marco", "Carlos", "Rafael", "Pablo", "Mateo", "Santiago", "Lucas", "Leon", "Hugo", "Tomas", "Felix", "Bruno", "Nico", "Emilio"},
        Culture.SLAVIC, new String[]{"Ivan", "Dmitri", "Nikolai", "Boris", "Alexei", "Sergei", "Viktor", "Yuri", "Andrei", "Pavel", "Mikhail", "Oleg", "Igor", "Vlad", "Grigori"}
    );

    private static final Map<Culture, String[]> FEMALE_FIRST = Map.of(
        Culture.EUROPEAN, new String[]{"Eleanor", "Catherine", "Margaret", "Elizabeth", "Victoria", "Charlotte", "Alice", "Clara", "Rose", "Grace", "Beatrice", "Florence", "Harriet", "Edith", "Agnes"},
        Culture.ASIAN, new String[]{"Mei", "Yuna", "Sakura", "Hana", "Rin", "Aoi", "Lan", "Xia", "Lin", "Suki", "Nori", "Yuki", "Mika", "Ami", "Kayo"},
        Culture.NORDIC, new String[]{"Freya", "Astrid", "Sigrid", "Ingrid", "Helga", "Brynhild", "Siri", "Tove", "Saga", "Elin", "Hild", "Liv", "Solveig", "Runa", "Greta"},
        Culture.DESERT, new String[]{"Layla", "Fatima", "Amira", "Zahra", "Nadia", "Yasmin", "Salma", "Hana", "Maryam", "Soraya", "Farida", "Leila", "Nour", "Dalia", "Zara"},
        Culture.TROPICAL, new String[]{"Luna", "Isla", "Alma", "Lucia", "Sofia", "Valentina", "Camila", "Elena", "Paloma", "Flora", "Marisol", "Coral", "Bella", "Estrella", "Esperanza"},
        Culture.SLAVIC, new String[]{"Katya", "Natasha", "Olga", "Anya", "Daria", "Vera", "Irina", "Mila", "Svetlana", "Nina", "Zoya", "Lara", "Tanya", "Elena", "Nadia"}
    );

    private static final Map<Culture, String[]> LAST_NAMES = Map.of(
        Culture.EUROPEAN, new String[]{"Blackwood", "Ashford", "Cromwell", "Thatcher", "Fairfax", "Whitmore", "Caldwell", "Thornton", "Hartley", "Pembroke"},
        Culture.ASIAN, new String[]{"Tanaka", "Yamamoto", "Wang", "Zhang", "Li", "Suzuki", "Watanabe", "Sato", "Liu", "Kim"},
        Culture.NORDIC, new String[]{"Eriksson", "Johansson", "Andersson", "Svensson", "Karlsson", "Nilsson", "Lindberg", "Holm", "Berg", "Storm"},
        Culture.DESERT, new String[]{"Al-Rashid", "Ibn-Khalil", "Al-Farsi", "El-Amin", "Al-Hakim", "Ibn-Zaid", "Al-Sharif", "El-Nouri", "Al-Bakr", "Ibn-Saad"},
        Culture.TROPICAL, new String[]{"Del Mar", "Cordero", "Vega", "Flores", "Rios", "Cruz", "Navarro", "Mendoza", "Castillo", "Reyes"},
        Culture.SLAVIC, new String[]{"Volkov", "Petrov", "Ivanov", "Kozlov", "Sokolov", "Novak", "Koval", "Morozov", "Popov", "Orlov"}
    );

    public static String random(Culture culture, Gender gender) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String[] firsts = (gender == Gender.FEMALE ? FEMALE_FIRST : MALE_FIRST).getOrDefault(culture, MALE_FIRST.get(Culture.EUROPEAN));
        String[] lasts = LAST_NAMES.getOrDefault(culture, LAST_NAMES.get(Culture.EUROPEAN));

        String first = firsts[r.nextInt(firsts.length)];
        String last = lasts[r.nextInt(lasts.length)];

        // Asian cultures: last name first
        if (culture == Culture.ASIAN) {
            return last + " " + first;
        }
        return first + " " + last;
    }

    public static String random() {
        return random(Culture.EUROPEAN, Gender.random());
    }

    private ResidentNames() {}
}
