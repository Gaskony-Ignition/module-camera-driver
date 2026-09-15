declare module '@inductiveautomation/perspective-client' {
    import { ComponentType } from 'react';

    export interface ComponentProps {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // Ignition SDK exposes component props as a generic string-keyed map; no SDK type info available
        props: Record<string, any>;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // emit() accepts and returns arbitrary Ignition prop objects; no SDK type info available
        emit: (options?: { classes?: string[]; style?: Record<string, any> }) => Record<string, any>;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // store is an opaque Ignition MobX store object; type not available without full SDK
        store?: any;
    }

    export interface PropertyTree {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // Ignition PropertyTree.read() returns an untyped value; callers must cast
        read(key: string, defaultValue?: any): any;
        readString(key: string, defaultValue?: string): string;
        readNumber(key: string, defaultValue?: number): number;
        readBoolean(key: string, defaultValue?: boolean): boolean;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // readArray() returns untyped elements; callers cast to the expected element type
        readArray(key: string, defaultValue?: any[]): any[];
    }

    export interface SizeObject {
        width: number;
        height: number;
    }

    // Opaque Ignition MobX component store handed to a ComponentStoreDelegate.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    export type ComponentStore = any;

    /**
     * Base class for a component's client-side delegate. It exchanges events with the
     * component's gateway-side ComponentModelDelegate over the authenticated Perspective
     * channel: fireEvent() reaches the gateway delegate's handleEvent(EventFiredMsg), and
     * the gateway delegate's fireEvent() reaches this delegate's handleEvent().
     */
    export abstract class ComponentStoreDelegate {
        constructor(componentStore: ComponentStore);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        fireEvent(eventName: string, eventObject: Record<string, any>): void;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        abstract handleEvent(eventName: string, eventObject: Record<string, any>): void;
    }

    export interface ComponentMeta {
        getComponentType(): string;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // Ignition SDK requires ComponentType<any> — the framework passes its own untyped props object
        getViewComponent(): ComponentType<any>;
        getDefaultSize(): SizeObject;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        // Ignition SDK declares this as any; implementations may return a typed object (assignable to any)
        getPropsReducer(tree: PropertyTree): any;
        // Optional: create the client-side delegate that talks to the gateway ComponentModelDelegate.
        // The framework looks for exactly this name: p.createDelegate(this)
        createDelegate?(componentStore: ComponentStore): ComponentStoreDelegate;
    }

    export namespace ComponentRegistry {
        function register(component: ComponentMeta): void;
    }
}
