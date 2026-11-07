package com.github.jamesg7.hacktheburghxiii.explain;

/**
 * Asks an {@link ExplanationProvider} for a node that isn't in the store yet.
 *
 * @param id     the id the link pointed to; the generated node is stored under this id
 * @param label  the link's label as the user saw it, e.g. "string concatenation"
 * @param parent the node whose explanation contained the link
 * @param depth  depth of the requested node in the chain (the first node shown is depth 0)
 */
public record ExplanationRequest(String id, String label, ExplanationNode parent, int depth) {}
