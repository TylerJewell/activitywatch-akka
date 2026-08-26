package io.akka.activitywatch.domain.query;

import io.akka.activitywatch.domain.query.QueryException.QueryInterpretException;
import io.akka.activitywatch.domain.query.QueryException.QueryParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six kinds of thing a query is made of — SPEC-001 §3 R68.
 *
 * <p>Each kind knows two things: how much of a string it claims ({@code check}) and what that
 * claimed text means ({@code parse}). The split matters because the *remainder* each kind
 * leaves behind is what decides how an argument list is cut up — R72 — and the six do not
 * agree about it. A string, an integer and a variable leave the character after themselves
 * alone; a call, a dict and a list consume one more.
 */
public sealed interface QueryToken {

  /** What a token evaluates to. */
  Object interpret(QueryDatastore datastore, Map<String, Object> namespace);

  /** The text a kind claimed, and what is left after it. A null claim means "not mine". */
  record Claim(String token, String rest) {
    boolean matched() {
      return token != null && !token.isEmpty();
    }
  }

  /** The kinds, in the order the parser tries them — R68. */
  enum Kind {
    STRING, INTEGER, FUNCTION, DICT, LIST, VARIABLE;

    Claim check(String text) {
      return switch (this) {
        case STRING -> QString.check(text);
        case INTEGER -> QInteger.check(text);
        case FUNCTION -> QFunction.check(text);
        case DICT -> QDict.check(text);
        case LIST -> QList.check(text);
        case VARIABLE -> QVariable.check(text);
      };
    }

    QueryToken parse(String token, Map<String, Object> namespace) {
      return switch (this) {
        case STRING -> QString.parse(token);
        case INTEGER -> QInteger.parse(token);
        case FUNCTION -> QFunction.parse(token, namespace);
        case DICT -> QDict.parse(token, namespace);
        case LIST -> QList.parse(token, namespace);
        case VARIABLE -> QVariable.parse(token, namespace);
      };
    }
  }

  // ---------------------------------------------------------------- integer

  record QInteger(long value) implements QueryToken {
    @Override
    public Object interpret(QueryDatastore datastore, Map<String, Object> namespace) {
      return value;
    }

    static Claim check(String text) {
      int i = 0;
      while (i < text.length() && Character.isDigit(text.charAt(i))) {
        i++;
      }
      return new Claim(text.substring(0, i), text.substring(i));
    }

    static QueryToken parse(String token) {
      return new QInteger(Long.parseLong(token));
    }
  }

  // ----------------------------------------------------------------- string

  record QString(String value) implements QueryToken {
    @Override
    public Object interpret(QueryDatastore datastore, Map<String, Object> namespace) {
      return value;
    }

    static Claim check(String text) {
      // The original indexes the first character unguarded and lets the IndexError escape,
      // which reaches a caller as a 500. Same outcome, named.
      QueryParser.requireNonEmpty(text);
      char quote = text.charAt(0);
      if (quote != '"' && quote != '\'') {
        return new Claim("", text);
      }
      StringBuilder token = new StringBuilder().append(quote);
      Character previous = null;
      for (int i = 1; i < text.length(); i++) {
        char c = text.charAt(i);
        token.append(c);
        if (c == quote && (previous == null || previous != '\\')) {
          break;
        }
        previous = c;
      }
      String claimed = token.toString();
      if (claimed.charAt(claimed.length() - 1) != quote || claimed.length() < 2) {
        throw new QueryParseException("Failed to parse string");
      }
      return new Claim(claimed, text.substring(claimed.length()));
    }

    static QueryToken parse(String token) {
      char quote = token.charAt(0);
      String unescaped = token.replace("\\" + quote, String.valueOf(quote));
      return new QString(unescaped.substring(1, unescaped.length() - 1));
    }
  }

  // --------------------------------------------------------------- variable

  record QVariable(String name, Object value) implements QueryToken {
    @Override
    public Object interpret(QueryDatastore datastore, Map<String, Object> namespace) {
      if (!namespace.containsKey(name)) {
        throw new QueryInterpretException(
            "Tried to reference variable '" + name + "' which is not defined");
      }
      namespace.put(name, value);
      return value;
    }

    static Claim check(String text) {
      int i = 0;
      while (i < text.length()) {
        char c = text.charAt(i);
        if (Character.isLetter(c) || c == '_') {
          i++;
        } else if (i != 0 && Character.isDigit(c)) {
          i++;
        } else {
          break;
        }
      }
      return new Claim(text.substring(0, i), text.substring(i));
    }

    static QueryToken parse(String token, Map<String, Object> namespace) {
      return new QVariable(token, namespace.get(token));
    }
  }

  // ------------------------------------------------------------------- list

  record QList(List<QueryToken> value) implements QueryToken {
    @Override
    public Object interpret(QueryDatastore datastore, Map<String, Object> namespace) {
      List<Object> out = new ArrayList<>(value.size());
      for (QueryToken token : value) {
        out.add(token.interpret(datastore, namespace));
      }
      return out;
    }

    static Claim check(String text) {
      QueryParser.requireNonEmpty(text);
      if (text.charAt(0) != '[') {
        return new Claim(null, text);
      }
      int i = QueryParser.balanced(text, '[', ']');
      return new Claim(text.substring(0, i), QueryParser.tail(text, i));
    }

    static QueryToken parse(String token, Map<String, Object> namespace) {
      String entries = token.substring(1, token.length() - 1);
      List<QueryToken> values = new ArrayList<>();
      while (!entries.isEmpty()) {
        entries = entries.strip();
        if (!values.isEmpty()) {
          QueryParser.requireNonEmpty(entries);
          if (entries.charAt(0) == ',') {
            entries = entries.substring(1);
          }
        }
        QueryParser.Taken taken = QueryParser.takeToken(entries);
        if (taken == null) {
          throw new QueryParseException("List expected a value, got nothing");
        }
        values.add(taken.kind().parse(taken.token(), namespace));
        entries = taken.rest();
      }
      return new QList(List.copyOf(values));
    }
  }

  // ------------------------------------------------------------------- dict

  record QDict(Map<String, QueryToken> value) implements QueryToken {
    @Override
    public Object interpret(QueryDatastore datastore, Map<String, Object> namespace) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, QueryToken> entry : value.entrySet()) {
        out.put(entry.getKey(), entry.getValue().interpret(datastore, namespace));
      }
      return out;
    }

    static Claim check(String text) {
      QueryParser.requireNonEmpty(text);
      if (text.charAt(0) != '{') {
        return new Claim(null, text);
      }
      int i = QueryParser.balanced(text, '{', '}');
      return new Claim(text.substring(0, i), QueryParser.tail(text, i));
    }

    static QueryToken parse(String token, Map<String, Object> namespace) {
      String entries = token.substring(1, token.length() - 1);
      Map<String, QueryToken> out = new LinkedHashMap<>();
      while (!entries.isEmpty()) {
        entries = entries.strip();
        if (!out.isEmpty()) {
          QueryParser.requireNonEmpty(entries);
          if (entries.charAt(0) == ',') {
            entries = entries.substring(1);
          }
        }
        QueryParser.Taken key = QueryParser.takeToken(entries);
        if (key == null || key.kind() != Kind.STRING) {
          throw new QueryParseException("Key in dict is not a str");
        }
        String name = ((QString) QString.parse(key.token())).value();
        entries = key.rest().strip();
        if (entries.isEmpty() || entries.charAt(0) != ':') {
          throw new QueryParseException("Key in dict is not followed by a :");
        }
        entries = entries.substring(1);
        QueryParser.Taken value = QueryParser.takeToken(entries);
        if (value == null) {
          throw new QueryParseException("Dict expected a value, got nothing");
        }
        out.put(name, value.kind().parse(value.token(), namespace));
        entries = value.rest();
      }
      return new QDict(Collections.unmodifiableMap(out));
    }
  }

  // --------------------------------------------------------------- function

  record QFunction(String name, List<QueryToken> args) implements QueryToken {
    @Override
    public Object interpret(QueryDatastore datastore, Map<String, Object> namespace) {
      QueryFunctions.Signature signature = QueryFunctions.lookup(name);
      if (signature == null) {
        throw new QueryInterpretException(
            "Tried to call function '" + name + "' which doesn't exist");
      }
      List<Object> callArgs = new ArrayList<>(args.size());
      for (QueryToken arg : args) {
        callArgs.add(arg.interpret(datastore, namespace));
      }
      return signature.call(datastore, namespace, callArgs);
    }

    /**
     * The name and the balanced bracket run after it.
     *
     * <p>The remainder is one character past the closing bracket. That extra character is what
     * makes R72's argument-dropping happen, and it is deliberate here.
     */
    static Claim check(String text) {
      int i = 0;
      boolean found = false;
      while (i < text.length()) {
        char c = text.charAt(i);
        if (Character.isLetter(c) || c == '_') {
          i++;
        } else if (i != 0 && Character.isDigit(c)) {
          i++;
        } else if (c == '(') {
          i++;
          found = true;
          break;
        } else {
          break;
        }
      }
      if (!found) {
        return new Claim(null, text);
      }
      int toConsume = 1;
      boolean single = false;
      boolean doubled = false;
      Character previous = null;
      for (int j = i; j < text.length(); j++) {
        char c = text.charAt(j);
        i = j + 1;
        if (c == '\'' && (previous == null || previous != '\\') && !doubled) {
          single = !single;
        } else if (c == '"' && (previous == null || previous != '\\') && !single) {
          doubled = !doubled;
        } else if (single || doubled) {
          // inside a literal
        } else if (c == '(') {
          toConsume++;
        } else if (c == ')') {
          toConsume--;
        }
        if (toConsume == 0) {
          break;
        }
        previous = c;
      }
      if (toConsume != 0) {
        return new Claim(null, text);
      }
      return new Claim(text.substring(0, i), QueryParser.tail(text, i));
    }

    /**
     * The arguments, cut up exactly as the original cuts them — R72.
     *
     * <p>After each argument the parser skips to the next comma. A kind whose {@code check}
     * already consumed that comma therefore skips one too many, and the argument between them
     * is silently dropped.
     */
    static QueryToken parse(String text, Map<String, Object> namespace) {
      int open = text.indexOf('(');
      String name = text.substring(0, open < 0 ? text.length() : open);
      String argsStr = text.substring(open + 1, Math.max(open + 1, text.length() - 1));
      List<QueryToken> args = new ArrayList<>();
      while (!argsStr.isEmpty()) {
        QueryParser.Taken taken = QueryParser.takeToken(argsStr);
        if (taken == null) {
          break;
        }
        argsStr = taken.rest();
        int comma = argsStr.indexOf(',');
        if (comma != -1) {
          argsStr = argsStr.substring(comma + 1);
        }
        args.add(taken.kind().parse(taken.token(), namespace));
      }
      return new QFunction(name, List.copyOf(args));
    }
  }
}
