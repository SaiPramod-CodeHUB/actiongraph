package com.saipramod.actiongraph.extract;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves deadline phrases relative to the meeting date: "by Friday", "EOD", "next Wednesday", "the 20th", "Oct 14". */
public final class DeadlineParser {

    public record Deadline(LocalDate date, String text) { }

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "monday", DayOfWeek.MONDAY, "tuesday", DayOfWeek.TUESDAY, "wednesday", DayOfWeek.WEDNESDAY,
            "thursday", DayOfWeek.THURSDAY, "friday", DayOfWeek.FRIDAY, "saturday", DayOfWeek.SATURDAY,
            "sunday", DayOfWeek.SUNDAY);

    private static final Pattern NEXT_DAY = Pattern.compile("\\bnext (monday|tuesday|wednesday|thursday|friday)\\b");
    private static final Pattern DAY = Pattern.compile("\\b(?:by|on|before|until|this|due)?\\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b");
    private static final Pattern ORDINAL = Pattern.compile("\\bthe (\\d{1,2})(?:st|nd|rd|th)\\b");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.? (\\d{1,2})\\b");

    private DeadlineParser() { }

    public static Deadline find(String sentence, LocalDate meetingDate) {
        String s = sentence.toLowerCase(Locale.ROOT);
        Matcher m;
        if (s.matches(".*\\b(eod|end of (the )?day|today|tonight|this afternoon)\\b.*")) {
            return new Deadline(meetingDate, phrase(s, "eod|end of (the )?day|today|tonight|this afternoon"));
        }
        if (s.matches(".*\\btomorrow\\b.*")) {
            return new Deadline(meetingDate.plusDays(1), "tomorrow");
        }
        if (s.matches(".*\\b(eow|end of (the )?week|this week)\\b.*")) {
            return new Deadline(meetingDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)),
                    phrase(s, "eow|end of (the )?week|this week"));
        }
        if (s.matches(".*\\bnext week\\b.*")) {
            return new Deadline(meetingDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays(4), "next week");
        }
        if ((m = NEXT_DAY.matcher(s)).find()) {
            LocalDate nextMonday = meetingDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return new Deadline(nextMonday.with(TemporalAdjusters.nextOrSame(DAYS.get(m.group(1)))), m.group());
        }
        if ((m = DAY.matcher(s)).find()) {
            return new Deadline(meetingDate.with(TemporalAdjusters.next(DAYS.get(m.group(1)))), m.group().strip());
        }
        if ((m = MONTH_DAY.matcher(s)).find()) {
            Month month = month(m.group(1));
            LocalDate d = LocalDate.of(meetingDate.getYear(), month, Integer.parseInt(m.group(2)));
            return new Deadline(d.isBefore(meetingDate) ? d.plusYears(1) : d, m.group());
        }
        if ((m = ORDINAL.matcher(s)).find()) {
            int day = Integer.parseInt(m.group(1));
            LocalDate d = meetingDate.withDayOfMonth(Math.min(day, meetingDate.lengthOfMonth()));
            return new Deadline(d.isBefore(meetingDate) ? d.plusMonths(1) : d, m.group());
        }
        return null;
    }

    private static String phrase(String s, String regex) {
        Matcher m = Pattern.compile("\\b(" + regex + ")\\b").matcher(s);
        return m.find() ? m.group() : regex;
    }

    private static Month month(String abbrev) {
        return switch (abbrev.substring(0, 3)) {
            case "jan" -> Month.JANUARY;
            case "feb" -> Month.FEBRUARY;
            case "mar" -> Month.MARCH;
            case "apr" -> Month.APRIL;
            case "may" -> Month.MAY;
            case "jun" -> Month.JUNE;
            case "jul" -> Month.JULY;
            case "aug" -> Month.AUGUST;
            case "sep" -> Month.SEPTEMBER;
            case "oct" -> Month.OCTOBER;
            case "nov" -> Month.NOVEMBER;
            default -> Month.DECEMBER;
        };
    }
}
