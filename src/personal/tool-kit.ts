import { Type, type TSchema } from "typebox";
import type { AnyAgentTool } from "../agents/tools/common.js";
import { jsonResult } from "../agents/tools/common.js";

/** Small wrapper so every personal tool is declared the same way. */
export function personalTool<S extends TSchema>(def: {
  name: string;
  label: string;
  description: string;
  parameters: S;
  run: (params: Record<string, unknown>, signal?: AbortSignal) => Promise<unknown>;
}): AnyAgentTool {
  return {
    name: def.name,
    label: def.label,
    description: def.description,
    parameters: def.parameters,
    execute: async (_id: string, params: unknown, signal?: AbortSignal) => {
      signal?.throwIfAborted();
      return jsonResult(await def.run((params ?? {}) as Record<string, unknown>, signal));
    },
  } as AnyAgentTool;
}

export const str = (description: string) => Type.String({ description });
export const optStr = (description: string) => Type.Optional(Type.String({ description }));
export const strList = (description: string) =>
  Type.Optional(Type.Array(Type.String(), { description }));

export function reqStr(params: Record<string, unknown>, key: string): string {
  const v = params[key];
  if (typeof v !== "string" || !v.trim()) {
    throw new Error(`${key} is required`);
  }
  return v.trim();
}

export function optList(params: Record<string, unknown>, key: string): string[] {
  const v = params[key];
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
}
