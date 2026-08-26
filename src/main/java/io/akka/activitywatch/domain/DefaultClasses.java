package io.akka.activitywatch.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The categories a client uses when the server has none set — SPEC-001 §3 R87.
 *
 * <p>Thirteen rules, each a regular expression over an event's values. Three are marked
 * case-insensitive and the rest are not, which is the original's own inconsistency and is kept
 * because a category is only useful if the same event lands in the same one on both systems.
 */
public final class DefaultClasses {

  private DefaultClasses() {}

  public static List<Object> defaults() {
    List<Object> classes = new java.util.ArrayList<>();
    classes.add(entry(List.of("Work"), "Google Docs|libreoffice|ReText", false));
    classes.add(entry(List.of("Work", "Programming"),
        "GitHub|Stack Overflow|BitBucket|Gitlab|vim|Spyder|kate|Ghidra|Scite", false));
    classes.add(entry(List.of("Work", "Programming", "ActivityWatch"),
        "ActivityWatch|aw-", true));
    classes.add(entry(List.of("Work", "Image"), "Gimp|Inkscape", false));
    classes.add(entry(List.of("Work", "Video"), "Kdenlive", false));
    classes.add(entry(List.of("Work", "Audio"), "Audacity", false));
    classes.add(entry(List.of("Work", "3D"), "Blender", false));
    classes.add(entry(List.of("Media", "Games"), "Minecraft|RimWorld", false));
    classes.add(entry(List.of("Media", "Video"), "YouTube|Plex|VLC", false));
    classes.add(entry(List.of("Media", "Social Media"),
        "reddit|Facebook|Twitter|Instagram|devRant", true));
    classes.add(entry(List.of("Media", "Music"), "Spotify|Deezer", true));
    classes.add(entry(List.of("Comms", "IM"),
        "Messenger|Telegram|Signal|WhatsApp|Rambox|Slack|Riot|Discord|Nheko", false));
    classes.add(entry(List.of("Comms", "Email"), "Gmail|Thunderbird|mutt|alpine", false));
    return List.copyOf(classes);
  }

  private static List<Object> entry(List<String> category, String regex, boolean ignoreCase) {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("type", "regex");
    rule.put("regex", regex);
    if (ignoreCase) {
      rule.put("ignore_case", true);
    }
    return List.of(category, rule);
  }
}
