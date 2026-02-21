declare module '@inductiveautomation/perspective-client' {
    import { ComponentType } from 'react';

    export interface ComponentProps {
        props: Record<string, any>;
        emit: (options?: { classes?: string[]; style?: Record<string, any> }) => Record<string, any>;
        store?: any;
    }

    export interface PropertyTree {
        read(key: string, defaultValue?: any): any;
        readString(key: string, defaultValue?: string): string;
        readNumber(key: string, defaultValue?: number): number;
        readBoolean(key: string, defaultValue?: boolean): boolean;
        readArray(key: string, defaultValue?: any[]): any[];
    }

    export interface SizeObject {
        width: number;
        height: number;
    }

    export interface ComponentMeta {
        getComponentType(): string;
        getViewComponent(): ComponentType<any>;
        getDefaultSize(): SizeObject;
        getPropsReducer(tree: PropertyTree): any;
    }
}
